package com.byd.extend;

import android.content.Context;
import android.graphics.Typeface;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Resolves the actual face/variable weight, including on Android 8 without custom-family APIs. */
public final class OptionalFonts {
    private final Context context;
    private final OptionalPacks packs;
    private final Map<String, Typeface> cache = new HashMap<>();
    public OptionalFonts(Context context) {
        this.context = context.getApplicationContext();
        packs = OptionalPacks.get(context);
    }
    public synchronized Typeface get(int index, int style) {
        OptionalPackCatalog.Pack pack = OptionalPackCatalog.font(index);
        boolean ready = packs.state(pack.key()).ready();
        String key = pack.key() + ":" + style + ":" + ready;
        Typeface cached = cache.get(key);
        if (cached != null) return cached;
        Typeface result;
        if (pack.bundled() || !ready) result = baseline(style);
        else {
            try {
                File directory = OptionalPackFiles.content(packs.root(), pack);
                boolean bold = (style & Typeface.BOLD) != 0;
                boolean italic = (style & Typeface.ITALIC) != 0;
                String prefix = "motion_" + pack.id;
                File variable = new File(directory, prefix + (italic ? "_italic_variable.ttf" : "_variable.ttf"));
                if (!variable.isFile()) variable = new File(directory, prefix + "_variable.ttf");
                if (variable.isFile()) {
                    boolean realItalic = variable.getName().contains("_italic_");
                    result = new Typeface.Builder(variable).setWeight(bold ? 700 : 400).setItalic(realItalic)
                            .setFontVariationSettings("'wght' " + (bold ? 700 : 400)).setFallback("sans-serif").build();
                    if (italic && !realItalic) result = Typeface.create(result, style);
                } else {
                    String suffix = bold && italic ? "bolditalic" : bold ? "bold" : italic ? "italic" : "regular";
                    File face = new File(directory, prefix + "_" + suffix + ".ttf");
                    boolean exact = face.isFile();
                    if (!exact) face = new File(directory, prefix + "_regular.ttf");
                    result = new Typeface.Builder(face).setWeight(exact && bold ? 700 : 400)
                            .setItalic(exact && italic).setFallback("sans-serif").build();
                    if (!exact) result = Typeface.create(result, style);
                }
                if (result == null) throw new IllegalStateException("Typeface builder returned null: " + pack.key());
            } catch (RuntimeException failure) {
                CameraHelperService.logViewingEvent(context, "optional_pack_font_error", pack.key(),
                        CameraViewingDiagnostics.describe(failure));
                result = baseline(style);
            }
        }
        cache.put(key, result);
        return result;
    }
    private Typeface baseline(int style) {
        return Typeface.create(context.getResources().getFont(R.font.motion_arimo), style);
    }
}
