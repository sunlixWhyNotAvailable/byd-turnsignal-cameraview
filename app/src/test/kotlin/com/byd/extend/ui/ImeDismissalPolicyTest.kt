package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ImeDismissalPolicyTest {
    @Test
    fun hiddenImeDoesNotCommitUntilTheFocusedOwnerHasSeenItVisible() {
        val policy = ImeDismissalPolicy()
        val owner = Any()
        var commits = 0

        policy.claim(owner) { commits++ }
        policy.onImeVisibility(owner, false)
        policy.onImeVisibility(owner, true)
        policy.onImeVisibility(owner, true)
        assertEquals(0, commits)

        policy.onImeVisibility(owner, false)
        policy.onImeVisibility(owner, false)
        assertEquals(1, commits)
    }

    @Test
    fun focusTransferMakesOldImeEventsAndCallbacksStale() {
        val policy = ImeDismissalPolicy()
        val first = Any()
        val second = Any()
        var firstCommits = 0
        var secondCommits = 0

        policy.claim(first) { firstCommits++ }
        policy.onImeVisibility(first, true)
        policy.claim(second) { secondCommits++ }
        policy.onImeVisibility(first, false)
        policy.onImeVisibility(second, false)
        assertEquals(0, firstCommits)
        assertEquals(0, secondCommits)

        policy.onImeVisibility(second, true)
        policy.onImeVisibility(second, false)
        assertEquals(1, secondCommits)
    }

    @Test
    fun everyActiveComposeTextEditorUsesTheSharedDismissalPolicy() {
        val primitives = source("UiPrimitives.kt")
        val mirror = source("MirrorScreen.kt")
        val app = source("BydExtendApp.kt")

        assertTrue(primitives.contains("ImeDismissalEffect(focused, ::finishEditing)"))
        assertTrue(mirror.contains("ImeDismissalEffect(hexFocused, ::finishHexEditing)"))
        assertTrue(app.contains("LocalImeDismissalPolicy provides imeDismissalPolicy"))
        assertEquals(1, Regex("BasicTextField\\(").findAll(primitives).count())
        assertEquals(1, Regex("BasicTextField\\(").findAll(mirror).count())
        assertFalse(app.contains("EditText("))
    }

    @Test
    fun avasPercentEditorIsNarrowAndHasBoundedOnePointSteps() {
        val app = source("BydExtendApp.kt")
        val start = app.indexOf("identity = \"avas-volume-")
        val field = app.substring((start - 500).coerceAtLeast(0), start + 80)

        assertTrue(field.contains("0f..100f, adjustable = true, slider = true"))
        assertTrue(field.contains("narrowInput = true"))
        val primitives = source("UiPrimitives.kt")
        assertTrue(primitives.contains("fun adjust(delta: Float)"))
        assertTrue(primitives.contains("+ delta).coerceIn(range)"))
        assertTrue(primitives.contains("NumberStep(\"−\""))
        assertTrue(primitives.contains("NumberStep(\"+\""))
    }

    private fun source(name: String): String {
        var path = Path.of("src/main/kotlin/com/byd/extend/ui", name)
        if (!Files.exists(path)) path = Path.of("app").resolve(path)
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }
}
