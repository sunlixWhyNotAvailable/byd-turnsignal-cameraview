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
                    + "SHA-256: ABC123";

    @Test
    public void selectsAllAppLanguagesAndPreservesMarkdown() {
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(BODY, "en"));
        assertEquals("# Українська\n\n- **Виправлено** оновлення.",
                ReleaseNotesSelector.select(BODY, "uk"));
        assertEquals("# 简体中文\n\n- **修复了**更新。",
                ReleaseNotesSelector.select(BODY, "zh-CN"));
    }

    @Test
    public void missingEmptyOrMalformedTranslationFallsBackToEnglish() {
        String missing = BODY.replace(
                "<!-- bydextend:release-notes:uk -->\n# Українська\n\n- **Виправлено** оновлення.\n"
                        + "<!-- /bydextend:release-notes:uk -->\n\n",
                "");
        String empty = BODY.replace("# 简体中文\n\n- **修复了**更新。", "   ");
        String malformed = BODY.replace("<!-- /bydextend:release-notes:uk -->", "");

        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(missing, "uk"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(empty, "zh-CN"));
        assertEquals("# English\n\n- **Fixed** the `update` flow.",
                ReleaseNotesSelector.select(malformed, "uk"));
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
}
