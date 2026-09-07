package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AppLanguageTest {
    @Test
    public void freshInstallStartsEnglishAndInitializationPersistsTheChoice() {
        TestSharedPreferences preferences = new TestSharedPreferences();

        assertEquals(AppLanguage.ENGLISH, AppLanguage.read(preferences));
        assertEquals(AppLanguage.ENGLISH, AppLanguage.initialize(null, preferences));
        assertEquals(AppLanguage.ENGLISH,
                preferences.getString(AppLanguage.KEY, "missing"));
        assertTrue(preferences.getBoolean(AppLanguage.KEY_INITIALIZED, false));
    }

    @Test
    public void anyPreexistingApplicationSettingMigratesAKeylessInstallToUkrainian() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putString("future_setting_unknown_to_language_code", "present").apply();

        assertEquals(AppLanguage.UKRAINIAN, AppLanguage.read(preferences));
        assertEquals(AppLanguage.UKRAINIAN, AppLanguage.initialize(null, preferences));
        assertEquals(AppLanguage.UKRAINIAN,
                preferences.getString(AppLanguage.KEY, "missing"));
    }

    @Test
    public void explicitChineseChoiceIsStable() {
        TestSharedPreferences preferences = new TestSharedPreferences();

        AppLanguage.write(preferences, "zh-rCN");

        assertEquals(AppLanguage.CHINESE, AppLanguage.read(preferences));
        assertEquals("zh-CN", AppLanguage.locale(AppLanguage.read(preferences)).toLanguageTag());
    }

    @Test
    public void emptyLegacyStoreKeepsUkrainianButFreshEmptyStoreStartsEnglish() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertEquals(AppLanguage.ENGLISH, AppLanguage.initialLanguage(preferences, false));
        assertEquals(AppLanguage.UKRAINIAN, AppLanguage.initialLanguage(preferences, true));
    }

    @Test
    public void explicitChoiceWinsOverPackageUpdateHistory() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        AppLanguage.write(preferences, AppLanguage.ENGLISH);
        assertEquals(AppLanguage.ENGLISH, AppLanguage.initialLanguage(preferences, true));
        AppLanguage.write(preferences, AppLanguage.CHINESE);
        assertEquals(AppLanguage.CHINESE, AppLanguage.initialLanguage(preferences, true));
    }
}
