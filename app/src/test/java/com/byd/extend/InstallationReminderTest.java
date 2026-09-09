package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallationReminderTest {
    @Test public void acknowledgesOneInstallationIncludingSameVersionReplacement() {
        TestSharedPreferences markers = new TestSharedPreferences();
        String first = InstallationReminder.identity(100, 100);
        InstallationReminder reminder = new InstallationReminder(markers, first);
        assertTrue(reminder.pending());
        reminder.acknowledge();
        assertFalse(new InstallationReminder(markers, first).pending());
        InstallationReminder replacement = new InstallationReminder(markers,
                InstallationReminder.identity(100, 200));
        assertTrue(replacement.pending());
        replacement.acknowledge();
        assertFalse(replacement.pending());
        assertTrue(new InstallationReminder(markers,
                InstallationReminder.identity(300, 300)).pending());
    }

    @Test public void markerDoesNotChangeUserSettingsOrFreshLanguage() {
        TestSharedPreferences settings = new TestSharedPreferences();
        InstallationReminder reminder = new InstallationReminder(new TestSharedPreferences(), "1:2");
        assertEquals(AppLanguage.ENGLISH, AppLanguage.read(settings));
        settings.edit().putBoolean("auto_start", false).putFloat("camera_x", .23f)
                .putString(AppLanguage.KEY, AppLanguage.CHINESE).apply();
        java.util.Map<String, ?> before = new java.util.HashMap<>(settings.getAll());
        reminder.acknowledge();
        assertEquals(before, settings.getAll());
        assertEquals(AppLanguage.CHINESE, AppLanguage.read(settings));
    }
}
