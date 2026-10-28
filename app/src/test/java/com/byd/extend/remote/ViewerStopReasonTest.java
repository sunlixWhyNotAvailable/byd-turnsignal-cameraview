package com.byd.extend.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerStopReasonTest {
    @Test public void retainsCodesWithoutLoggingBrowserMessagesUrlsOrCredentials() throws Exception {
        JSONObject cause = new JSONObject().put("origin", "playback_error").put("category", "hls")
                .put("mediaCode", 3).put("exception", "NotSupportedError")
                .put("hlsType", "mediaError").put("hlsDetails", "bufferAppendError")
                .put("fragment", 7).put("httpStatus", 404)
                .put("url", "https://private.example/?password=secret").put("message", "secret");
        assertEquals("origin=playback_error category=hls exception=NotSupportedError hlsType=mediaError"
                + " hlsDetails=bufferAppendError mediaCode=3 fragment=7 httpStatus=404", ViewerStopReason.describe(cause));
        cause.put("exception", "secret\r\nforged_log").put("fragment", -1).put("httpStatus", "token");
        assertFalse(ViewerStopReason.describe(cause).contains("secret"));
        assertFalse(ViewerStopReason.describe(cause).contains("fragment="));
        assertFalse(ViewerStopReason.describe(cause).contains("httpStatus="));
        assertEquals("origin=pagehide", ViewerStopReason.describe(new JSONObject().put("origin", "pagehide")));
    }

    @Test public void classifiesLegacyCurrentAndStaleMediaWithoutExposingIdentifiers() {
        FragmentedVideoWindow current = new FragmentedVideoWindow();
        FragmentedVideoWindow retired = new FragmentedVideoWindow();
        String playlist = current.getPlaylistPath();
        String serverRevision = " serverRevision=" + ViewerStopReason.WEB_REVISION;
        assertEquals("resource=playlist generation=legacy clientRevision=unknown" + serverRevision,
                ViewerStopReason.mediaRequest("/live.m3u8", playlist, null));
        assertEquals("resource=playlist generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist, playlist, "2"));
        assertEquals("resource=playlist generation=stale clientRevision=1" + serverRevision,
                ViewerStopReason.mediaRequest(retired.getPlaylistPath(), playlist, "1"));
        assertEquals("resource=init generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist.replace("live.m3u8", "init.mp4"), playlist, "2"));
        assertEquals("resource=segment generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist.replace("live.m3u8", "segment/7.m4s"), playlist, "2"));
        assertEquals("resource=playlist generation=none clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist, null, "2"));
        assertEquals("resource=other generation=invalid clientRevision=unknown" + serverRevision,
                ViewerStopReason.mediaRequest("/media/secret/private?password=secret", playlist, "secret\r\nforged"));
    }

    @Test public void browserRevisionIsNumericAndBounded() throws Exception {
        JSONObject cause = new JSONObject().put("origin", "playback_error").put("clientRevision", 2);
        assertEquals("origin=playback_error clientRevision=2", ViewerStopReason.describe(cause));
        for (Object invalid : new Object[] {-1, 2.5, 1_000_001, "secret"}) {
            cause.put("clientRevision", invalid);
            assertEquals("origin=playback_error", ViewerStopReason.describe(cause));
        }
    }

    @Test public void transportMeasurementsPreserveCountsButExcludeUntrustedPayloads() throws Exception {
        JSONObject sample = new JSONObject().put("reason", "stall").put("requests", 93)
                .put("completed", 7).put("aborted", 86).put("receivedBytes", 9924255)
                .put("completedBytes", 1608091).put("ready", 1).put("seeking", 1)
                .put("url", "https://private.example/?token=secret").put("message", "secret");
        assertEquals("reason=stall requests=93 completed=7 aborted=86 receivedBytes=9924255"
                + " completedBytes=1608091 ready=1 seeking=1", ViewerStopReason.transport(sample));
        sample.put("reason", "secret\nforged");
        assertNull(ViewerStopReason.transport(sample));
        sample.put("reason", "progress").put("requests", "secret").put("completed", -1)
                .put("aborted", 1.5).put("receivedBytes", 9_007_199_254_740_992L).put("ready", 5);
        assertEquals("reason=progress completedBytes=1608091 seeking=1", ViewerStopReason.transport(sample));
    }

    @Test public void recoveryMeasurementsRetainActualSpeedAndBoundedPositions() throws Exception {
        JSONObject sample = new JSONObject().put("reason", "recovery")
                .put("bufferedRecoveries", 2).put("missingFragments", 3).put("lastMissingSequence", 103)
                .put("recoveryFromMs", 8020).put("recoveryTargetMs", 12400).put("playbackRatePermille", 1500)
                .put("url", "https://private.example/?token=secret");
        assertEquals("reason=recovery bufferedRecoveries=2 missingFragments=3 lastMissingSequence=103"
                + " recoveryFromMs=8020 recoveryTargetMs=12400 playbackRatePermille=1500",
                ViewerStopReason.transport(sample));
        sample.put("playbackRatePermille", 16001).put("recoveryFromMs", -1).put("lastMissingSequence", "secret");
        assertEquals("reason=recovery bufferedRecoveries=2 missingFragments=3 recoveryTargetMs=12400",
                ViewerStopReason.transport(sample));
    }
}
