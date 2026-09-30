package com.byd.extend;

/** Selects app-language content from a GitHub release-notes body. */
public final class ReleaseNotesSelector {
    private static final String ENGLISH = "en";
    private static final String UKRAINIAN = "uk";
    private static final String CHINESE = "zh-CN";
    private static final String RUSSIAN = "ru";
    private static final String[] LANGUAGES = {ENGLISH, UKRAINIAN, CHINESE, RUSSIAN};

    private ReleaseNotesSelector() {}

    /** Returns the requested nonempty block, then English, then the original unsectioned body. */
    public static String select(String body, String language) {
        if (body == null || body.isEmpty()) return body == null ? "" : body;
        String requestedLanguage = languageFor(language);
        String requested = block(body, requestedLanguage);
        if (requested != null) return requested;
        String english = ENGLISH.equals(requestedLanguage) ? null : block(body, ENGLISH);
        return english != null ? english : body;
    }

    /** Keeps every release's raw body and applies locale fallback independently per entry. */
    static String selectHistory(
            Iterable<AppUpdateManager.ReleaseNotesEntry> history, String language) {
        StringBuilder notes = new StringBuilder();
        for (AppUpdateManager.ReleaseNotesEntry entry : history) {
            if (notes.length() > 0) notes.append("\n\n---\n\n");
            notes.append("## v").append(entry.version).append("\n\n")
                    .append(select(entry.body, language));
        }
        return notes.toString();
    }

    private static String languageFor(String language) {
        if (UKRAINIAN.equals(language) || "ua".equals(language)) return UKRAINIAN;
        if (CHINESE.equals(language) || "zh".equals(language)) return CHINESE;
        if (RUSSIAN.equals(language)) return RUSSIAN;
        return ENGLISH;
    }

    private static String block(String body, String language) {
        String open = marker(language, false);
        String close = marker(language, true);
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int openIndex = -1;
        int closeIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (open.equals(lines[index])) {
                if (openIndex >= 0) return null;
                openIndex = index;
            }
            if (close.equals(lines[index])) {
                if (closeIndex >= 0) return null;
                closeIndex = index;
            }
        }
        if (openIndex < 0 || closeIndex <= openIndex) return null;

        StringBuilder content = new StringBuilder();
        for (int index = openIndex + 1; index < closeIndex; index++) {
            if (isMarker(lines[index])) return null;
            if (content.length() > 0) content.append('\n');
            content.append(lines[index]);
        }
        String selected = content.toString().trim();
        return selected.isEmpty() ? null : selected;
    }

    private static boolean isMarker(String line) {
        for (String language : LANGUAGES) {
            if (marker(language, false).equals(line) || marker(language, true).equals(line)) {
                return true;
            }
        }
        return false;
    }

    private static String marker(String language, boolean closing) {
        return "<!-- " + (closing ? "/" : "")
                + "bydextend:release-notes:" + language + " -->";
    }
}
