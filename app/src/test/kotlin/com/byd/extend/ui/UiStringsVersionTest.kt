package com.byd.extend.ui

import com.byd.extend.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStringsVersionTest {
    @Test fun headerUsesThePackageVersionInEveryLanguage() {
        for (language in UiLanguage.values()) {
            assertTrue(UiStrings(language).subtitle.endsWith("| v${BuildConfig.VERSION_NAME}"))
        }
    }
}
