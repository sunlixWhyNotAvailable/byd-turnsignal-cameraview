package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import org.junit.Test;

public final class AdbTlsIdentityTest {
    @Test public void generatedCertificateUsesExistingRsaWithoutGlobalProviderMutation()
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String[] providersBefore = providerNames();

        X509Certificate certificate = AdbTlsIdentity.create(pair);

        assertEquals(((RSAPublicKey) pair.getPublic()).getModulus(),
                ((RSAPublicKey) certificate.getPublicKey()).getModulus());
        certificate.verify(pair.getPublic());
        assertArrayEquals(providersBefore, providerNames());
    }

    private static String[] providerNames() {
        return Arrays.stream(Security.getProviders()).map(Provider::getName).toArray(String[]::new);
    }
}
