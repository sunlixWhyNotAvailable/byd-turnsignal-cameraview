package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AppUpdateManagerReleaseHistoryTest {
    @Test
    public void releaseHistoryPagesKeepOnlyStableVersionsInInstalledToOfferedRange() throws Exception {
        Map<String, AppUpdateManager.ReleaseNotesEntry> history = new HashMap<>();
        JSONArray firstPage = new JSONArray()
                .put(release("1.4.2", "newer than offer"))
                .put(release("1.4.1", "offered"))
                .put(release("1.4.0", "installed"))
                .put(release("1.3.9", "older"))
                .put(release("1.3.8-beta.1", "prerelease").put("prerelease", true))
                .put(release("1.3.7", "draft").put("draft", true));

        assertTrue(AppUpdateManager.collectReleaseNotesPage(
                firstPage, "1.4.0", "1.4.1", history));
        assertEquals(1, history.size());
        assertEquals("offered", history.get("1.4.1").body);
    }

    @Test
    public void duplicateVersionsAreKeptOnceAndUnsupportedStableTagsMarkHistoryPartial() throws Exception {
        Map<String, AppUpdateManager.ReleaseNotesEntry> history = new HashMap<>();
        JSONArray firstPage = new JSONArray().put(release("1.4.1", "first"));
        JSONArray secondPage = new JSONArray()
                .put(release("1.4.1", "duplicate"))
                .put(release("bad-tag", "unsupported"));

        assertTrue(AppUpdateManager.collectReleaseNotesPage(
                firstPage, "1.4.0", "1.4.1", history));
        assertFalse(AppUpdateManager.collectReleaseNotesPage(
                secondPage, "1.4.0", "1.4.1", history));
        assertEquals(1, history.size());
        assertEquals("first", history.get("1.4.1").body);
    }

    @Test
    public void finalHistoryIsDescendingAndKeepsLatestReleaseBody() throws Exception {
        Map<String, AppUpdateManager.ReleaseNotesEntry> history = new HashMap<>();
        JSONArray page = new JSONArray()
                .put(release("1.3.3", "older"))
                .put(release("1.4.1", "list copy"));
        AppUpdateManager.collectReleaseNotesPage(page, "1.3.2", "1.4.1", history);

        AppUpdateManager.ReleaseHistory result = AppUpdateManager.orderedReleaseHistory(
                history, "1.4.1", "latest endpoint body", false);

        assertEquals(2, result.entries.size());
        assertEquals("1.4.1", result.entries.get(0).version);
        assertEquals("latest endpoint body", result.entries.get(0).body);
        assertEquals("1.3.3", result.entries.get(1).version);
        assertFalse(result.complete);
    }

    @Test
    public void historyEndpointMustUseTheFixedGitHubRepositoryAndPagination() {
        String valid = "https://api.github.com/repos/sunlixWhyNotAvailable/"
                + "byd-turnsignal-cameraview/releases?per_page=30&page=2";

        assertEquals(valid, AppUpdateManager.requireTrustedReleaseHistoryApiUrl(valid));
        assertFalse(isTrusted("https://example.com/repos/sunlixWhyNotAvailable/"
                + "byd-turnsignal-cameraview/releases?per_page=30&page=2"));
        assertFalse(isTrusted("https://api.github.com/repos/other/repo/releases"
                + "?per_page=30&page=2"));
        assertFalse(isTrusted("https://api.github.com/repos/sunlixWhyNotAvailable/"
                + "byd-turnsignal-cameraview/releases?per_page=100&page=2"));
    }

    private static boolean isTrusted(String url) {
        try {
            AppUpdateManager.requireTrustedReleaseHistoryApiUrl(url);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static JSONObject release(String version, String body) throws Exception {
        return new JSONObject().put("tag_name", "v" + version).put("body", body);
    }
}
