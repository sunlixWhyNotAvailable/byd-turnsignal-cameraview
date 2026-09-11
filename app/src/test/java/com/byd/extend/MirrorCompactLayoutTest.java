package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Source wiring and dp-budget checks only; not a rendered-device assertion. */
public final class MirrorCompactLayoutTest {
    @Test
    public void cameraWorkspaceInheritsTheExistingCompactScreenScope() throws Exception {
        String mirror = source("MirrorScreen.kt");
        assertTrue(Pattern.compile("ScreenSurface\\(colors, scroll = false, compact = true\\) \\{\\s*CameraWorkspace\\(")
                .matcher(mirror).find());
        assertTrue(mirror.contains("0f..16f, adjustable = true, slider = true"));
    }

    @Test
    public void colorDialogExplicitlyScopesAllThreeNumericChannelsAsCompact() throws Exception {
        String mirror = source("MirrorScreen.kt");
        assertTrue(Pattern.compile("CompositionLocalProvider\\(LocalCompactControls provides true\\) \\{\\s*"
                + "listOf\\(16 to \"R\", 8 to \"G\", 0 to \"B\"\\)")
                .matcher(mirror).find());
        assertTrue(mirror.contains("range = 0f..255f, adjustable = true, slider = true"));
    }

    @Test
    public void compactFixedControlsLeaveAUsableTrackInTheActualSidebar() throws Exception {
        String primitives = source("UiPrimitives.kt");
        String numeric = primitives.substring(primitives.indexOf("internal fun NumericSetting("),
                primitives.indexOf("internal fun normalizeSliderValue("));
        String widthExpression = "Modifier\\.width\\(if \\(compact\\) (\\d+)\\.dp else (\\d+)\\.dp\\)";
        int inputStart = numeric.indexOf("BasicTextField(");
        int labelWidth = number(numeric.substring(0, inputStart), widthExpression);
        Matcher widths = Pattern.compile(widthExpression).matcher(numeric.substring(inputStart));
        int widestInput = 0;
        int count = 0;
        while (widths.find()) {
            widestInput = Math.max(widestInput, Integer.parseInt(widths.group(1)));
            count++;
        }
        assertEquals("regular and narrow input alternatives must use compact widths", 2, count);
        // Width alternatives are exclusive; budget the larger input, not both simultaneously.
        int compactWidths = labelWidth + widestInput;
        int gap = number(numeric, "Arrangement\\.spacedBy\\(if \\(compact\\) (\\d+)\\.dp");
        int step = number(primitives.substring(primitives.indexOf("private fun NumberStep(")),
                "Modifier\\.size\\((\\d+)\\.dp\\)");
        int suffixUpperBound = number(numeric.substring(numeric.indexOf("Text(suffix,")),
                "Modifier\\.width\\((\\d+)\\.dp\\)");
        int sidebar = number(source("CameraUiCommon.kt"), "Column\\(Modifier\\.width\\((\\d+)\\.dp\\)");
        int inset = number(primitives, "if \\(compact && bodyPadding == 14\\.dp\\) (\\d+)\\.dp");
        // Even reserving the full non-compact suffix width is conservative: compact adjustable
        // controls actually give an empty RGB suffix zero width and dp only its text width.
        int fixed = compactWidths + 2 * step + suffixUpperBound + 5 * gap;
        assertTrue("border-width track must retain at least 100 dp", sidebar - 2 * inset - fixed >= 100);
        String dialog = source("MirrorScreen.kt").split("private fun MirrorBorderColorPicker\\(", 2)[1];
        int dialogWidth = number(dialog, "Column\\(Modifier\\.width\\((\\d+)\\.dp\\)");
        int dialogInset = number(dialog, "verticalScroll\\(rememberScrollState\\(\\)\\)\\.padding\\((\\d+)\\.dp\\)");
        assertTrue("RGB tracks must retain at least 200 dp", dialogWidth - 2 * dialogInset - fixed >= 200);
    }

    private static int number(String text, String expression) {
        Matcher match = Pattern.compile(expression).matcher(text);
        assertTrue("missing layout expression: " + expression, match.find());
        return Integer.parseInt(match.group(1));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/kotlin/com/byd/extend/ui", name);
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
