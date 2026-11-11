package com.byd.extend;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class OptionalPackSelectionTest {
    @Test public void migrationIncludesDisabledEngineAndInactiveSavedFonts() throws Exception {
        String avas = "{\"engine\":{\"enabled\":false,\"packId\":\"huracan_v10\"}}";
        String templates = "{\"active\":\"base:lens\",\"saved\":["
                + "{\"id\":\"unused\",\"texts\":[{\"font\":2},{\"font\":7}]},"
                + "{\"id\":\"other\",\"texts\":[{\"font\":2},{\"font\":0}]}]}";
        Set<String> expected = Set.of("engine-huracan_v10-v1", "font-cousine-v1", "font-lobster-v1");
        assertEquals(expected, OptionalPackSelection.required(avas, templates));
        assertTrue(OptionalPackSelection.required(null, null).isEmpty());
        assertTrue(OptionalPackSelection.required(AvasConfig.empty().toJson(), "{\"saved\":[]}").isEmpty());
    }
}
