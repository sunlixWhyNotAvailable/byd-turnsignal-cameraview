package com.byd.extend;

/** Pure ownership and wake-policy decisions for the AVAS recovery chain. */
final class AvasRecoveryPolicy {
    private AvasRecoveryPolicy() {}

    static boolean anyEnabledAvasAudio(AvasConfig config) {
        if (config == null) return false;
        for (AvasConfig.Profile profile : config.profiles) {
            if (profile.enabled) return true;
        }
        return config.engine.enabled
                && (config.engine.exteriorEnabled || config.engine.interiorEnabled);
    }

    static boolean daemonRequired(
            boolean recoveryAllowed, boolean userShutdown, boolean anyProfileEnabled) {
        return recoveryAllowed && !userShutdown && anyProfileEnabled;
    }

    static boolean wakeRequired(
            boolean runtimeAdmitted, boolean userShutdown, boolean anyProfileEnabled) {
        return runtimeAdmitted && !userShutdown && anyProfileEnabled;
    }

    static boolean legacyHelperRecoveryEnabled(
            boolean ordinaryRecovery, boolean daemonRequired) {
        return ordinaryRecovery && !daemonRequired;
    }

    static int userIdForUid(int uid) {
        return uid < 0 ? 0 : uid / 100_000;
    }

    static boolean installedIdentityMatches(
            int expectedUid, String expectedIdentity,
            Integer installedUid, String installedSourceDir, Long installedUpdateTime) {
        if (expectedUid < 0 || expectedIdentity == null || installedUid == null
                || installedSourceDir == null || installedUpdateTime == null) {
            return false;
        }
        return expectedUid == installedUid
                && expectedIdentity.equals(installedSourceDir + ":" + installedUpdateTime);
    }
}
