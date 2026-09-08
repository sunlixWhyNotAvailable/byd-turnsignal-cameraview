package com.byd.extend.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisabledSwitchAppearanceTest {
    @Test
    fun disabledAppearanceIsNeutralWhileCheckedStillControlsGeometry() {
        val primitives = File("src/main/kotlin/com/byd/extend/ui/UiPrimitives.kt").readText()
        val row = primitives.substring(
            primitives.indexOf("internal fun SwitchLine("),
            primitives.indexOf("internal fun AppSwitch("),
        )
        val switch = primitives.substring(
            primitives.indexOf("internal fun AppSwitch("),
            primitives.indexOf("internal fun Segmented("),
        )

        assertTrue(row.contains("if (enabled) colors.text else colors.muted.copy(alpha = .62f)"))
        assertTrue(row.contains("if (enabled) colors.muted else colors.muted.copy(alpha = .52f)"))
        assertTrue(switch.contains("if (enabled && checked) colors.accent else colors.disabled"))
        assertTrue(switch.contains("if (!enabled) colors.muted.copy(alpha = .45f)"))
        assertTrue(switch.contains("if (checked) knob else knobOff"))
        assertTrue(switch.contains("if (checked) width - knob - 3.dp else 3.dp"))
        assertFalse(switch.contains("if (pending)"))
        assertFalse(switch.contains("colors.yellow"))
    }
}
