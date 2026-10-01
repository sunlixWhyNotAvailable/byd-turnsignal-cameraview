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
    public static final String MASERATI_V8 = "maserati_v8";
    public static final String G500_V8 = "g500_v8";
    public static final String GOLF_GTI_L4 = "golf_gti_l4";
    public static final String PORSCHE_GT3_H6 = "porsche_gt3_h6";
    public static final String HARLEY_VTWIN = "harley_vtwin";
    public static final String DEFAULT_PACK_ID = FERRARI_V8;
    public static final List<String> PACK_IDS = Collections.unmodifiableList(Arrays.asList(
            FERRARI_V8, JAGUAR_V6, HURACAN_V10, GERMAN_L4,
            MASERATI_V8, G500_V8, GOLF_GTI_L4, PORSCHE_GT3_H6, HARLEY_VTWIN));

    private AvasEngineSettings() { }
}
