package com.byd.extend;

import android.content.pm.PackageManager;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real flag selection and failure classification; no PackageManager results are fabricated. */
public class AppUpdateManagerTest {
    @SuppressWarnings("deprecation")
    @Test public void androidNineAndTenArchiveLookupCollectsCertificates() {
        for (int sdk : new int[]{28, 29, 30, 35}) {
            int flags = AppUpdateManager.archiveSignatureFlags(sdk);
            assertNotEquals(0, flags & PackageManager.GET_SIGNATURES);
            assertNotEquals(0, flags & PackageManager.GET_SIGNING_CERTIFICATES);
        }
        assertEquals(PackageManager.GET_SIGNATURES, AppUpdateManager.archiveSignatureFlags(26));
    }

    @Test public void onlySignerFailuresGetSignerCategories() {
        assertEquals(AppUpdateManager.Failure.SIGNER_UNAVAILABLE, AppUpdateManager.failureOf(
                new AppUpdateManager.SignerException(
                        AppUpdateManager.Failure.SIGNER_UNAVAILABLE, "unavailable")));
        assertEquals(AppUpdateManager.Failure.SIGNER_MISMATCH, AppUpdateManager.failureOf(
                new AppUpdateManager.SignerException(
                        AppUpdateManager.Failure.SIGNER_MISMATCH, "mismatch")));
        assertEquals(AppUpdateManager.Failure.OTHER,
                AppUpdateManager.failureOf(new SecurityException("Downloaded APK is not newer")));
        assertEquals(AppUpdateManager.Failure.OTHER,
                AppUpdateManager.failureOf(new IOException("network")));
    }
}
