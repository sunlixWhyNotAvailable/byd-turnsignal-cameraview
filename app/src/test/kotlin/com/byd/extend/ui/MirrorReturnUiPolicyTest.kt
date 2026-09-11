package com.byd.extend.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MirrorReturnUiPolicyTest {
    @Test
    fun returnSwitchIsTheVisibilityBindingFooterWithAllLocales() {
        val source = File("src/main/kotlin/com/byd/extend/ui/MirrorScreen.kt").readText()
        val visibilityRow = source.substringAfter(
            "CameraButtonBindings.Action.MirrorVisibility, state.visibilityBinding")
            .substringBefore("Text(strings.text(")

        assertTrue(visibilityRow.contains("MirrorReturnOnAppOpen"))
        assertTrue(visibilityRow.contains("Повертати при відкритті застосунку"))
        assertTrue(visibilityRow.contains("Restore when opening the app"))
        assertTrue(visibilityRow.contains("打开应用时恢复显示"))
        assertTrue(source.contains("footer: @Composable ColumnScope.() -> Unit = {}"))
        assertTrue(source.contains("footer()"))
    }
}
