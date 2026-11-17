package com.byd.extend;

import com.byd.extend.artwork.ArtworkReadbackDiagnostics;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ArtworkReadbackDiagnosticsTest {
    @Test public void logsMismatchAndReadOnlyRecoveryWithoutExposingIdentifiers() throws Exception {
        ArtworkReadbackDiagnostics state = new ArtworkReadbackDiagnostics();
        JSONObject first = state.written("private track", "/private/expected.png", 1, 26,
                1, 26, "/private/old.png", 0, 10, 11, 12, 13);
        assertEquals("mismatch", first.getString("state"));
        assertFalse(first.toString().contains("private"));
        assertNull(state.observed("private track", "/private/expected.png", 1, 26, "/private/old.png", 20, 21));
        JSONObject recovered = state.observed("private track", "/private/expected.png", 1, 26, "/private/expected.png", 30, 31);
        assertEquals("recovered", recovered.getString("state"));
        assertEquals(18, recovered.getLong("duration_ms"));
        assertEquals(2, recovered.getInt("mismatch_observations"));
        assertNull(state.finish("ended_unverified", 32));
        assertNotNull(state.written("private track", "/private/expected.png", 1, 26,
                1, 26, "/private/old.png", 0, 40, 41, 42, 43));
        assertEquals("superseded", state.observed("next", "/private/expected.png", 1, 26,
                "/private/expected.png", 44, 45).getString("state"));
    }
}
