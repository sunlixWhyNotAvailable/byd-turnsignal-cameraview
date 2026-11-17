package com.byd.extend;

import com.byd.extend.artwork.ArtworkDirectPolicy;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ArtworkDirectPolicyTest {
    private static final String PREFIX = "/storage/emulated/0/Download/.byd-extend-artwork/" + "a".repeat(32) + "/";
    private static final String PNG = "b".repeat(64) + ".png";

    @Test public void recordedSl07PropertiesProduceTheVerifiedHostPath() throws Exception {
        // capture121111: vm=cell2, single_os=0, user=0; does not claim native acceptance on other cells.
        String root = ArtworkDirectPolicy.hostMediaRoot("cell2", "0");
        assertEquals("/data/cells/cell2-rw/data/media/0/Download/.byd-extend-artwork/" + "a".repeat(32) + "/" + PNG,
                ArtworkDirectPolicy.hostPath(root, 0, PREFIX, PREFIX + PNG));
        String other = PREFIX.replace("/emulated/0/", "/emulated/10/");
        assertEquals("/data/cells/cell3-rw/data/media/10/Download/.byd-extend-artwork/" + "a".repeat(32) + "/" + PNG,
                ArtworkDirectPolicy.hostPath(ArtworkDirectPolicy.hostMediaRoot("cell3", "0"), 10, other, other + PNG));
        assertEquals("/data/media", ArtworkDirectPolicy.hostMediaRoot("", "1"));
    }

    @Test public void unknownConflictingAndForeignPathsCannotSilentlyBecomeCell2() {
        for (String[] props : new String[][]{{"", "0"}, {"cell2", ""}, {"cell2", "1"}, {"cell2/../cell3", "0"}})
            assertThrows(IOException.class, () -> ArtworkDirectPolicy.hostMediaRoot(props[0], props[1]));
        for (String path : new String[]{PREFIX + "../" + PNG, PREFIX + "foreign.png", PREFIX.replace("emulated/0", "emulated/10") + PNG})
            assertThrows(IOException.class, () -> ArtworkDirectPolicy.hostPath("/data/media", 0, PREFIX, path));
        assertThrows(IOException.class, () -> ArtworkDirectPolicy.hostPath("/data/media", 10, PREFIX, PREFIX + PNG));
    }

    @Test public void newImagesAreImmediateAndUnchangedImagesWaitForOneSecond() {
        assertEquals(0, ArtworkDirectPolicy.delay(1010, 1000, true, true));
        assertEquals(990, ArtworkDirectPolicy.delay(1010, 1000, false, true));
        assertEquals(0, ArtworkDirectPolicy.delay(2000, 1000, false, true));
        assertEquals(0, ArtworkDirectPolicy.delay(9000, 1000, false, true));
        // One send at the current time schedules one future repeat, not missed-interval catch-up.
        assertEquals(1000, ArtworkDirectPolicy.delay(9000, 9000, false, true));
        assertEquals(1000, ArtworkDirectPolicy.delay(9000, 1000, false, false));
    }

    @Test public void disabledReplacedAndSupersededSelectionsCannotPublish() {
        Object prepared = new Object();
        assertTrue(ArtworkDirectPolicy.current(1, 1, 4, 4, prepared, prepared));
        assertFalse(ArtworkDirectPolicy.current(1, 2, 4, 4, prepared, prepared));
        assertFalse(ArtworkDirectPolicy.current(1, 1, 4, 5, prepared, prepared));
        assertFalse(ArtworkDirectPolicy.current(1, 1, 4, 4, prepared, new Object()));
        assertFalse(ArtworkDirectPolicy.current(1, 1, 4, 4, prepared, null));
        assertFalse(ArtworkDirectPolicy.current(1, 1, 4, 4, null, null));
    }
}
