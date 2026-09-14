package com.byd.extend;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

/** Existing Extend RSA key plus a locally cached matching certificate for ADB TLS. */
final class AdbTlsIdentity {
    private static final String CERTIFICATE_FILE = "adb_tls_certificate.der";
    private static final long DAY_MS = 86_400_000L;

    final KeyPair keyPair;
    final X509Certificate certificate;

    private AdbTlsIdentity(KeyPair keyPair, X509Certificate certificate) throws Exception {
        if (!(keyPair.getPrivate() instanceof RSAPrivateCrtKey)
                || !(keyPair.getPublic() instanceof RSAPublicKey)
                || !(certificate.getPublicKey() instanceof RSAPublicKey)
                || !((RSAPublicKey) keyPair.getPublic()).getModulus().equals(
                        ((RSAPublicKey) certificate.getPublicKey()).getModulus())) {
            throw new java.io.IOException("ADB TLS certificate does not match existing RSA key");
        }
        certificate.checkValidity();
        certificate.verify(keyPair.getPublic());
        this.keyPair = keyPair;
        this.certificate = certificate;
    }

    static AdbTlsIdentity load(Context context) throws Exception {
        KeyPair pair = LocalAdbClient.loadOrCreateKeys(context.getApplicationContext());
        File directory = new File(context.getFilesDir(), "adb_keys");
        File certificateFile = new File(directory, CERTIFICATE_FILE);
        if (certificateFile.isFile()) {
            try (FileInputStream input = new FileInputStream(certificateFile)) {
                X509Certificate certificate = (X509Certificate) CertificateFactory
                        .getInstance("X.509").generateCertificate(input);
                return new AdbTlsIdentity(pair, certificate);
            } catch (Throwable ignored) {
                // The RSA identity remains untouched; only its derived certificate is rebuilt.
            }
        }
        X509Certificate certificate = create(pair);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create ADB identity directory");
        }
        try (FileOutputStream output = new FileOutputStream(certificateFile)) {
            output.write(certificate.getEncoded());
        }
        return new AdbTlsIdentity(pair, certificate);
    }

    static X509Certificate create(KeyPair pair) throws Exception {
        Provider provider = new BouncyCastleProvider();
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=BYD Extend ADB");
        BigInteger serial = new BigInteger(160, new SecureRandom()).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, new Date(now - DAY_MS),
                new Date(now + 3650L * DAY_MS), subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(provider).build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(provider).getCertificate(holder);
        return new AdbTlsIdentity(pair, certificate).certificate;
    }
}
