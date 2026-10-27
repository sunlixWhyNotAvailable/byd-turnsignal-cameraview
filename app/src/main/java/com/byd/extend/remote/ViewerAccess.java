package com.byd.extend.remote;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** One viewer lease; public authentication and session renewal happen before camera acquisition. */
public final class ViewerAccess {
    private static final long IDLE_MS = 30_000;
    private final SecureRandom random = new SecureRandom();
    private final byte[] salt = new byte[16];
    private final byte[] passwordHash;
    private final String privateProxyProof;
    private String session;
    private String pendingSession;
    private long lastSeen;
    private Long lastAuthentication;
    private boolean revoked;
    private final java.util.LinkedHashSet<String> replaced = new java.util.LinkedHashSet<>();

    public ViewerAccess(String password, String privateProxyProof) {
        random.nextBytes(salt);
        this.privateProxyProof = privateProxyProof;
        if (privateProxyProof == null && (password == null || password.isEmpty()))
            throw new IllegalArgumentException("A public viewer password is required");
        passwordHash = password == null || password.isEmpty() ? null : hash(password);
    }

    public synchronized boolean authenticate(String password, String proxyProof) {
        if (revoked) return false;
        if (privateProxyProof != null) return equal(privateProxyProof, proxyProof);
        return password != null && password.length() <= 1024
                && MessageDigest.isEqual(passwordHash, hash(password));
    }

    /** A current viewer can retry admission without renewing its lease or bypassing credentials. */
    synchronized boolean beginAuthentication(String candidate, long now) {
        if (!revoked && equal(session, candidate) && now - lastSeen < IDLE_MS) return true;
        if (lastAuthentication != null && now - lastAuthentication < 1_000L) return false;
        lastAuthentication = now;
        return true;
    }

    /** Prepare an authenticated candidate without revoking the current viewer. */
    public synchronized String reserveAdmission(String previousSession) {
        if (revoked) return null;
        if (equal(session, previousSession)) return session;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        pendingSession = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return pendingSession;
    }

    /** Called on the runtime owner thread at the point of global viewer takeover. */
    public synchronized boolean commitAdmission(String candidate, long now) {
        if (revoked) return false;
        if (!equal(session, candidate)) {
            if (!equal(pendingSession, candidate)) return false;
            if (session != null) rememberReplacement(session);
            session = pendingSession;
            pendingSession = null;
        }
        lastSeen = now;
        return true;
    }

    public synchronized void cancelAdmission(String candidate) {
        if (equal(pendingSession, candidate)) pendingSession = null;
    }

    public synchronized boolean accepts(String candidate, long now) {
        if (revoked || session == null || !equal(session, candidate)) return false;
        lastSeen = now;
        return true;
    }

    /** Missing HTTP requests mean an abandoned viewer, not permission to keep parked cameras alive. */
    public synchronized boolean expire(long now) {
        if (session == null || now - lastSeen < IDLE_MS) return false;
        session = null;
        return true;
    }

    public synchronized boolean release(String candidate) {
        if (session == null || !equal(session, candidate)) return false;
        session = null;
        return true;
    }

    public synchronized void revoke() { revoked = true; session = null; pendingSession = null; }
    public synchronized boolean occupied() { return session != null; }
    public synchronized String current() { return session; }
    public synchronized boolean isCurrent(String candidate) { return equal(session, candidate); }
    public synchronized boolean wasReplaced(String candidate) { return replaced.contains(candidate); }
    public synchronized void supersede(String candidate) {
        if (equal(session, candidate)) { rememberReplacement(session); session = null; }
    }

    private void rememberReplacement(String token) {
        replaced.add(token);
        // Only recent revoked tokens need a human-readable reason; all older tokens remain denied.
        if (replaced.size() > 128) replaced.remove(replaced.iterator().next());
    }

    private byte[] hash(String password) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Password hashing unavailable", e); }
        finally { spec.clearPassword(); }
    }

    private static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
