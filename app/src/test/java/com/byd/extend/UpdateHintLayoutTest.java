package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;

public final class UpdateHintLayoutTest {
    private static final long NOW_NS = 2_000_000_000L;
    private static final long NOW_MS = 2_000L;

    @Test
    public void sharedGoldenUsesThreeColumnColumnMajorGrid() throws Exception {
        JSONObject golden = resource("update-hint-layout-golden-v1.json");
        ArrayList<UpdateHintState> cards = new ArrayList<>();
        JSONArray encodedCards = golden.getJSONArray("cards");
        for (int i = 0; i < encodedCards.length(); i++) {
            JSONObject card = encodedCards.getJSONObject(i);
            cards.add(new UpdateHintState(1, card.getString("ownerPackage"),
                    card.getString("processSessionId"), card.getLong("revision"),
                    card.getString("eventId"), card.getString("phase"),
                    card.getLong("requestedAtElapsedNanos"), card.getLong("expiresAtElapsedMs"),
                    golden.getInt("displayId"), card.getInt("preferredSizePercent"),
                    card.getInt("preferredWidthPx"), card.getInt("preferredHeightPx")));
        }
        UpdateHintLayout.Result result = UpdateHintLayout.calculate(cards,
                golden.getInt("displayId"), golden.getInt("availableLeftPx"),
                golden.getInt("availableTopPx"), golden.getInt("availableWidthPx"),
                golden.getInt("availableHeightPx"), (float) golden.getDouble("density"),
                golden.getLong("nowElapsedNanos"), golden.getLong("nowElapsedMs"));

        assertEquals(golden.getInt("expectedRows"), result.rows);
        assertEquals(golden.getInt("expectedColumns"), result.columns);
        JSONArray x = golden.getJSONArray("expectedX");
        JSONArray y = golden.getJSONArray("expectedY");
        for (int i = 0; i < result.placements.size(); i++) {
            assertEquals(x.getInt(i), result.placements.get(i).xPx);
            assertEquals(y.getInt(i), result.placements.get(i).yPx);
        }
    }

    @Test
    public void rowsAndColumnsUseTheirLargestCardAndAlignRowTops() {
        UpdateHintLayout.Result result = UpdateHintLayout.calculate(Arrays.asList(
                        state("a", "0", 1, 100, 80, 40),
                        state("b", "1", 2, 100, 120, 70),
                        state("c", "2", 3, 100, 90, 60),
                        state("d", "3", 4, 100, 100, 30)),
                0, 0, 0, 264, 174, 1f, NOW_NS, NOW_MS);

        assertEquals(2, result.rows);
        assertEquals(2, result.columns);
        assertEquals(18, result.placements.get(0).xPx);
        assertEquals(18, result.placements.get(0).yPx);
        assertEquals(86, result.placements.get(1).yPx); // max row-zero height 60 + 8 gap
        assertEquals(146, result.placements.get(2).xPx); // max first-column width 120 + 8 gap
        assertEquals(18, result.placements.get(2).yPx);
    }

    @Test
    public void largestCommonCeilingCanDropBelowFiftyAndThenRegrow() {
        UpdateHintState large = state("com.byd.extend", "large", 1, 100, 400, 200);
        UpdateHintLayout.Result tight = UpdateHintLayout.calculate(Collections.singletonList(large),
                0, 0, 0, 136, 86, 1f, NOW_NS, NOW_MS);
        assertEquals(25, tight.placements.get(0).effectiveScalePercent);
        assertEquals(100, tight.placements.get(0).widthPx);
        assertEquals(50, tight.placements.get(0).heightPx);

        UpdateHintLayout.Result open = UpdateHintLayout.calculate(Collections.singletonList(large),
                0, 0, 0, 436, 236, 1f, NOW_NS, NOW_MS);
        assertEquals(100, open.placements.get(0).effectiveScalePercent);
        assertEquals(100, large.preferredSizePercent);
        assertEquals(400, large.preferredWidthPx);
    }

    @Test
    public void smallerPreferenceStopsShrinkingWhileLargerCardContinues() {
        UpdateHintLayout.Result result = UpdateHintLayout.calculate(Arrays.asList(
                        state("a", "small", 1, 50, 100, 50),
                        state("b", "large", 2, 100, 200, 100)),
                0, 0, 0, 294, 136, 1f, NOW_NS, NOW_MS);
        assertEquals(1, result.rows);
        assertEquals(50, result.placements.get(0).effectiveScalePercent);
        assertEquals(75, result.placements.get(1).effectiveScalePercent);
        assertEquals(100, result.placements.get(0).widthPx);
        assertEquals(150, result.placements.get(1).widthPx);
    }

    @Test
    public void onlyLocalPendingRetainsPlacementAfterSharedBudget() {
        UpdateHintState own = state("com.byd.extend", "own", 1_000_000_000L,
                100, 100, 50);
        UpdateHintState peer = state("com.bydhud.app", "peer", 1_000_000_000L,
                100, 100, 50);
        assertFalse(own.isActiveAt(1_500_000_000L, NOW_MS));

        UpdateHintLayout.Result result = UpdateHintLayout.calculate(Arrays.asList(own, peer),
                0, 0, 0, 200, 200, 1f, 1_500_000_000L, NOW_MS, "com.byd.extend");
        assertNotNull(result.find("com.byd.extend", "own"));
        assertNull(result.find("com.bydhud.app", "peer"));
    }

    @Test
    public void exactTimestampTieUsesHudExtendCollectorPriorityOnly() {
        UpdateHintLayout.Result result = UpdateHintLayout.calculate(Arrays.asList(
                        state("com.bydcollector.collector", "c", 1_900_000_000L, 100, 30, 30),
                        state("com.byd.extend", "e", 1_900_000_000L, 100, 30, 30),
                        state("com.bydhud.app", "h", 1_900_000_000L, 100, 30, 30)),
                0, 0, 0, 200, 200, 1f, NOW_NS, NOW_MS);
        assertEquals("com.bydhud.app", result.placements.get(0).ownerPackage);
        assertEquals("com.byd.extend", result.placements.get(1).ownerPackage);
        assertEquals("com.bydcollector.collector", result.placements.get(2).ownerPackage);
        assertTrue(result.placements.get(0).yPx < result.placements.get(1).yPx);
    }

    private static UpdateHintState state(String owner, String event, long requestedNs,
            int preferredPercent, int width, int height) {
        if (requestedNs < 1_000_000_000L) requestedNs += NOW_NS - 100_000_000L;
        return new UpdateHintState(1, owner, "session-" + owner, 1, event,
                UpdateHintState.PENDING, requestedNs, 0, 0, preferredPercent, width, height);
    }

    private static JSONObject resource(String name) throws Exception {
        try (InputStream stream = UpdateHintLayoutTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(stream);
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
