package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class UpdateHintProtocolContractTest {
    @Test
    public void visibleDeadlineStartsAtActualAppearanceAndNeverChangesWithGeometry() {
        UpdateHintState pending = new UpdateHintState(1, "com.byd.extend", "session", 7,
                "event", UpdateHintState.PENDING, 123, 0, 0, 100, 400, 100);
        UpdateHintState visible = pending.visible(5_000, 8);
        UpdateHintState moved = visible.withGeometry(80, 320, 80, 9);
        assertEquals(15_000, visible.expiresAtElapsedMs);
        assertEquals(15_000, moved.expiresAtElapsedMs);
        assertEquals(123, moved.requestedAtElapsedNanos);
    }

    @Test
    public void manifestAndTransportKeepExactV1Boundary() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        String source = read("app/src/main/java/com/byd/extend/UpdateHintCoordinator.java");
        assertTrue(manifest.contains("com.byd.apps.updatehint.COORDINATION"));
        assertTrue(manifest.contains("com.byd.apps.updatehint.PROTOCOL_VERSION"));
        assertTrue(manifest.contains("android:value=\"1\""));
        assertTrue(manifest.contains("android:name=\"com.bydhud.app\""));
        assertTrue(manifest.contains("android:name=\"com.bydcollector.collector\""));
        assertTrue(source.contains("public static final int SUBSCRIBE = 1"));
        assertTrue(source.contains("public static final int STATE = 2"));
        assertTrue(source.contains("public static final int UNSUBSCRIBE = 3"));
        assertTrue(source.contains("getPackagesForUid(message.sendingUid)"));
        assertTrue(source.contains("receivedInitial.containsAll(expectedInitial)"));
        assertTrue(source.contains("connection.remote != null && !sendState"));
        assertTrue(source.contains("INITIAL_BUDGET_NS = 500_000_000L"));
        assertTrue(source.contains("handleConfirmedDeath(peerPackage, boundSessionId)"));
        assertFalse(source.contains("handleConfirmedDeath(peerPackage, record.sessionId())"));
        assertFalse(source.contains("checkSignatures"));
        assertFalse(source.contains("http://"));
        assertFalse(source.contains("https://"));
        assertEquals(12, occurrences(source, "data.put"));
    }

    @Test
    public void staleVisibleAndMalformedActiveStatesAreRejectedByModel() {
        UpdateHintState visible = new UpdateHintState(1, "com.bydhud.app", "s", 2, "e",
                UpdateHintState.VISIBLE, 1, 100, 0, 100, 100, 50);
        assertTrue(visible.isValid());
        assertFalse(visible.isActiveAt(1_000_000_000L, 100));
        assertFalse(new UpdateHintState(1, "com.bydhud.app", "s", 2, "e",
                UpdateHintState.PENDING, 1, 5, 0, 100, 100, 50).isValid());
    }

    @Test
    public void sessionFenceRejectsDuplicatesReorderingAndRetiredSessions() {
        UpdateHintState.SessionRecord record = new UpdateHintState.SessionRecord();
        UpdateHintState oldVisible = active("old", 2, UpdateHintState.VISIBLE);
        assertTrue(record.accept(oldVisible));
        assertFalse(record.accept(active("old", 2, UpdateHintState.NONE)));
        assertFalse(record.accept(active("old", 1, UpdateHintState.NONE)));

        assertTrue(record.accept(active("new", 0, UpdateHintState.PENDING)));
        assertFalse(record.accept(active("old", 3, UpdateHintState.VISIBLE)));
        assertTrue(record.accept(active("new", 1, UpdateHintState.NONE)));
        assertFalse(record.confirmDeath("old"));
        assertTrue(record.confirmDeath("new"));
        assertFalse(record.accept(active("new", 2, UpdateHintState.VISIBLE)));
        assertTrue(record.accept(active("newest", 0, UpdateHintState.PENDING)));
    }

    @Test
    public void delayedOldBinderDeathCannotRemoveCurrentNewSession() {
        UpdateHintState.SessionRecord record = new UpdateHintState.SessionRecord();
        assertTrue(record.accept(active("binder-a-session", 1, UpdateHintState.VISIBLE)));
        assertTrue(record.accept(active("binder-b-session", 0, UpdateHintState.PENDING)));

        assertFalse(record.confirmDeath("binder-a-session"));
        assertEquals("binder-b-session", record.sessionId());
        assertEquals("binder-b-session", record.state().processSessionId);
    }

    @Test
    public void sameEventGeometryCannotResetOrderingDeadlineOrPhase() {
        UpdateHintState.SessionRecord record = new UpdateHintState.SessionRecord();
        UpdateHintState pending = active("session", 1, UpdateHintState.PENDING);
        assertTrue(record.accept(pending));
        assertFalse(record.accept(new UpdateHintState(1, "com.bydhud.app", "session", 2,
                "event", UpdateHintState.PENDING, 2, 0, 0, 80, 80, 40)));

        UpdateHintState visible = active("session", 2, UpdateHintState.VISIBLE);
        assertTrue(record.accept(visible));
        assertTrue(record.accept(visible.withGeometry(80, 80, 40, 3)));
        assertEquals(visible.requestedAtElapsedNanos, record.state().requestedAtElapsedNanos);
        assertEquals(visible.expiresAtElapsedMs, record.state().expiresAtElapsedMs);
        assertFalse(record.accept(active("session", 4, UpdateHintState.PENDING)));
        assertFalse(record.accept(new UpdateHintState(1, "com.bydhud.app", "session", 4,
                "event", UpdateHintState.VISIBLE, 1, 20_000, 0, 80, 80, 40)));

        assertTrue(record.accept(active("session", 4, UpdateHintState.NONE)));
        assertFalse(record.accept(active("session", 5, UpdateHintState.VISIBLE)));
    }

    @Test
    public void untrustedGeometryIsBoundedBeforeLayoutArithmetic() {
        assertFalse(new UpdateHintState(1, "com.bydhud.app", "s", 1, "e",
                UpdateHintState.PENDING, 1, 0, 0, 1_001, 100, 50).isValid());
        assertFalse(new UpdateHintState(1, "com.bydhud.app", "s", 1, "e",
                UpdateHintState.PENDING, 1, 0, 0, 100, 100_001, 50).isValid());
        assertFalse(new UpdateHintState(1, "com.bydhud.app", "s", 1, "e",
                UpdateHintState.PENDING, 1, 0, -1, 100, 100, 50).isValid());
    }

    private static UpdateHintState active(String session, long revision, String phase) {
        boolean none = UpdateHintState.NONE.equals(phase);
        return new UpdateHintState(1, "com.bydhud.app", session, revision,
                none ? "" : "event", phase, none ? 0 : 1,
                UpdateHintState.VISIBLE.equals(phase) ? 10_000 : 0, 0,
                none ? 0 : 100, none ? 0 : 100, none ? 0 : 50);
    }

    private static String read(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        if (!Files.exists(path) && relativePath.startsWith("app/")) {
            path = Path.of(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0;
                index += needle.length()) count++;
        return count;
    }
}
