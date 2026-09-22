package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
