package com.byd.extend;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fixed engine sound-pack catalog shared by configuration and UI. */
public final class AvasEngineSettings {
    public static final String FERRARI_V8 = "ferrari_v8";
    public static final String JAGUAR_V6 = "jaguar_v6";
    public static final String HURACAN_V10 = "huracan_v10";
    public static final String GERMAN_L4 = "german_l4";
    public static final String DEFAULT_PACK_ID = FERRARI_V8;
    public static final List<String> PACK_IDS = Collections.unmodifiableList(Arrays.asList(
            FERRARI_V8, JAGUAR_V6, HURACAN_V10, GERMAN_L4));

    private AvasEngineSettings() { }
}
