package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AvasRecoveryPolicyTest {
    @Test
    public void daemonRequiresAutoStartProfileAndNoExplicitShutdown() {
        assertTrue(AvasRecoveryPolicy.daemonRequired(true, false, true));
        assertFalse(AvasRecoveryPolicy.daemonRequired(false, false, true));
        assertFalse(AvasRecoveryPolicy.daemonRequired(true, true, true));
        assertFalse(AvasRecoveryPolicy.daemonRequired(true, false, false));
    }

    @Test
    public void manualRuntimeWakeDoesNotDependOnAutoStartOrScreenState() {
        assertTrue(AvasRecoveryPolicy.wakeRequired(true, false, true));
        assertFalse(AvasRecoveryPolicy.wakeRequired(false, false, true));
        assertFalse(AvasRecoveryPolicy.wakeRequired(true, true, true));
        assertFalse(AvasRecoveryPolicy.wakeRequired(true, false, false));
    }

    @Test
    public void oldHelperYieldsOnlyToHealthyDaemonOwnership() {
        assertTrue(AvasRecoveryPolicy.legacyHelperRecoveryEnabled(true, false));
        assertFalse(AvasRecoveryPolicy.legacyHelperRecoveryEnabled(true, true));
        assertFalse(AvasRecoveryPolicy.legacyHelperRecoveryEnabled(false, false));
    }

    @Test
    public void detectsAnyEnabledAutomaticProfile() {
        assertFalse(AvasRecoveryPolicy.anyAutomaticProfileEnabled(AvasConfig.empty()));
        List<AvasConfig.Profile> profiles = new ArrayList<>();
        for (String id : AvasConfig.PROFILE_IDS) {
            profiles.add(new AvasConfig.Profile(id, "unlock".equals(id), false,
                    15, "", Collections.emptyList()));
        }
        assertTrue(AvasRecoveryPolicy.anyAutomaticProfileEnabled(new AvasConfig(profiles)));
    }

    @Test
    public void installedIdentityRequiresUidPathAndUpdateTime() {
        assertTrue(AvasRecoveryPolicy.installedIdentityMatches(
                10123, "/data/app/current/base.apk:99",
                10123, "/data/app/current/base.apk", 99L));
        assertFalse(AvasRecoveryPolicy.installedIdentityMatches(
                10123, "/data/app/current/base.apk:99",
                null, null, null));
        assertFalse(AvasRecoveryPolicy.installedIdentityMatches(
                10123, "/data/app/current/base.apk:99",
                10124, "/data/app/current/base.apk", 99L));
        assertFalse(AvasRecoveryPolicy.installedIdentityMatches(
                10123, "/data/app/current/base.apk:99",
                10123, "/data/app/replaced/base.apk", 99L));
        assertFalse(AvasRecoveryPolicy.installedIdentityMatches(
                10123, "/data/app/current/base.apk:99",
                10123, "/data/app/current/base.apk", 100L));
    }

    @Test
    public void appUidSelectsItsEncodedAndroidUser() {
        assertTrue(AvasRecoveryPolicy.userIdForUid(10123) == 0);
        assertTrue(AvasRecoveryPolicy.userIdForUid(210123) == 2);
        assertTrue(AvasRecoveryPolicy.userIdForUid(-1) == 0);
    }
}
