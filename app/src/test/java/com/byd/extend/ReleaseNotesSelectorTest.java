package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReleaseNotesSelectorTest {
    private static final String BODY =
            "## v1.1.1\n\n"
                    + "<!-- bydextend:release-notes:en -->\n"
                    + "# English\n\n- **Fixed** the `update` flow.\n"
                    + "<!-- /bydextend:release-notes:en -->\n\n"
                    + "<!-- bydextend:release-notes:uk -->\n"
                    + "# Українська\n\n- **Виправлено** оновлення.\n"
                    + "<!-- /bydextend:release-notes:uk -->\n\n"
                    + "<!-- bydextend:release-notes:zh-CN -->\n"
                    + "# 简体中文\n\n- **修复了**更新。\n"
                    + "<!-- /bydextend:release-notes:zh-CN -->\n\n"
                    + "<!-- bydextend:release-notes:ru -->\n"
                    + "# Русский\n\n- **Исправлено** обновление.\n"
                    + "<!-- /bydextend:release-notes:ru -->\n\n"
                    + "SHA-256: ABC123";

    @Test
    public void selectsAllAppLanguagesAndPreservesMarkdown() {
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(BODY, "en"));
        assertEquals("# Українська\n\n- **Виправлено** оновлення.",
                ReleaseNotesSelector.select(BODY, "uk"));
        assertEquals("# 简体中文\n\n- **修复了**更新。",
                ReleaseNotesSelector.select(BODY, "zh-CN"));
        assertEquals("# Русский\n\n- **Исправлено** обновление.",
                ReleaseNotesSelector.select(BODY, "ru"));
    }

    @Test
    public void missingEmptyOrMalformedTranslationFallsBackToEnglish() {
        String missing = BODY.replace(
                "<!-- bydextend:release-notes:uk -->\n# Українська\n\n- **Виправлено** оновлення.\n"
                        + "<!-- /bydextend:release-notes:uk -->\n\n",
                "");
        String missingRussian = BODY.replace(
                "<!-- bydextend:release-notes:ru -->\n# Русский\n\n- **Исправлено** обновление.\n"
                        + "<!-- /bydextend:release-notes:ru -->\n\n",
                "");
        String empty = BODY.replace("# 简体中文\n\n- **修复了**更新。", "   ");
        String malformed = BODY.replace("<!-- /bydextend:release-notes:uk -->", "");

        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(missing, "uk"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(empty, "zh-CN"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(malformed, "uk"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(missingRussian, "ru"));
    }

    @Test
    public void duplicateNestedAndCrossedBlocksAreRejected() {
        String duplicateEnglish = BODY.replace(
                "<!-- bydextend:release-notes:en -->\n",
                "<!-- bydextend:release-notes:en -->\n<!-- bydextend:release-notes:en -->\n");
        String duplicateUkrainian = BODY.replace(
                "<!-- bydextend:release-notes:uk -->\n",
                "<!-- bydextend:release-notes:uk -->\n<!-- bydextend:release-notes:uk -->\n");
        String nested = "<!-- bydextend:release-notes:uk -->\n"
                + "Українська\n"
                + "<!-- bydextend:release-notes:zh-CN -->\n"
                + "简体中文\n"
                + "<!-- /bydextend:release-notes:zh-CN -->\n"
                + "<!-- /bydextend:release-notes:uk -->\n"
                + "<!-- bydextend:release-notes:en -->\nEnglish\n"
                + "<!-- /bydextend:release-notes:en -->";
        String crossed = "<!-- bydextend:release-notes:en -->\n"
                + "English\n"
                + "<!-- bydextend:release-notes:uk -->\n"
                + "Українська\n"
                + "<!-- /bydextend:release-notes:en -->\n"
                + "<!-- /bydextend:release-notes:uk -->";

        assertEquals(duplicateEnglish, ReleaseNotesSelector.select(duplicateEnglish, "en"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(duplicateUkrainian, "uk"));
        assertEquals("English", ReleaseNotesSelector.select(nested, "uk"));
        assertEquals(crossed, ReleaseNotesSelector.select(crossed, "uk"));
    }

    @Test
    public void markerMustOccupyAnExactLine() {
        String nearMiss = "prefix <!-- bydextend:release-notes:en -->\nEnglish\n"
                + "<!-- /bydextend:release-notes:en --> suffix";

        assertEquals(nearMiss, ReleaseNotesSelector.select(nearMiss, "en"));
    }

    @Test
    public void englishOnlyAndLegacyBodiesRemainReadable() {
        String englishOnly = "<!-- bydextend:release-notes:en -->\r\nEnglish\rnotes\r\n"
                + "<!-- /bydextend:release-notes:en -->";
        String legacy = "## Changes\r\n\r\n- legacy\r\nSHA-256: OLD";
        String ukrainianOnly = "<!-- bydextend:release-notes:uk -->\nУкраїнська\n"
                + "<!-- /bydextend:release-notes:uk -->";

        assertEquals("English\nnotes", ReleaseNotesSelector.select(englishOnly, "zh-CN"));
        assertEquals(legacy, ReleaseNotesSelector.select(legacy, "uk"));
        assertEquals(ukrainianOnly, ReleaseNotesSelector.select(ukrainianOnly, "en"));
        assertEquals(legacy, ReleaseNotesSelector.select(legacy, "unsupported"));
    }

    @Test
    public void releaseHistoryUsesRequestedLocaleAndFallsBackPerEntry() {
        String history = ReleaseNotesSelector.selectHistory(java.util.Arrays.asList(
                new AppUpdateManager.ReleaseNotesEntry("1.4.1", BODY),
                new AppUpdateManager.ReleaseNotesEntry("1.3.3", "## Changes\n\n- English only")), "ru");

        assertEquals("## v1.4.1\n\n# Русский\n\n- **Исправлено** обновление."
                + "\n\n---\n\n## v1.3.3\n\n## Changes\n\n- English only", history);
    }
}
