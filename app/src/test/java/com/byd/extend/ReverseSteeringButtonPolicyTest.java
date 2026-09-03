package com.byd.extend;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReverseSteeringButtonPolicyTest {
    @Test
    public void firstDownExcludesRepeatAndUp() {
        assertTrue(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_DOWN, 0));
        assertFalse(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_DOWN, 1));
        assertFalse(ReverseSteeringButtonPolicy.isFirstDown(KeyEvent.ACTION_UP, 0));
    }

    @Test
    public void mappedIdentityRequiresAssignedCode() {
        assertTrue(ReverseSteeringButtonPolicy.isMappedKey(42, 42));
        assertFalse(ReverseSteeringButtonPolicy.isMappedKey(41, 42));
        assertFalse(ReverseSteeringButtonPolicy.isMappedKey(42,
                ReverseSteeringButtonPreferences.UNASSIGNED));
    }

    @Test
    public void heldTailUsesDownTimeSoLostUpCannotSwallowFreshPress() {
        assertTrue(ReverseSteeringButtonPolicy.isHeldTail(42, 100L, 42, 100L));
        assertFalse(ReverseSteeringButtonPolicy.isHeldTail(42, 101L, 42, 100L));
        assertFalse(ReverseSteeringButtonPolicy.isHeldTail(41, 100L, 42, 100L));
    }

    @Test
    public void preferencesNormalizeAndResetBinding() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertTrue(ReverseSteeringButtonPreferences.load(preferences)
                == ReverseSteeringButtonPreferences.UNASSIGNED);
        ReverseSteeringButtonPreferences.save(preferences, 42);
        assertTrue(ReverseSteeringButtonPreferences.load(preferences) == 42);
        ReverseSteeringButtonPreferences.save(preferences, -9);
        assertTrue(ReverseSteeringButtonPreferences.load(preferences)
                == ReverseSteeringButtonPreferences.UNASSIGNED);
        preferences.edit().putString(ReverseSteeringButtonPreferences.KEY_CODE, "42").apply();
        assertTrue(ReverseSteeringButtonPreferences.load(preferences)
                == ReverseSteeringButtonPreferences.UNASSIGNED);
        ReverseSteeringButtonPreferences.save(preferences, 42);
        ReverseSteeringButtonPreferences.reset(preferences);
        assertTrue(ReverseSteeringButtonPreferences.load(preferences)
                == ReverseSteeringButtonPreferences.UNASSIGNED);
    }

    @Test
    public void captureThenReleaseConsumesTailAndDoesNotToggle() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        state.beginLearning();
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 42, 0, 100L, 7)
                == ReverseSteeringButtonPolicy.Decision.LEARNED);
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 42, 1, 100L, 42)
                == ReverseSteeringButtonPolicy.Decision.CONSUME);
        assertTrue(state.apply(KeyEvent.ACTION_UP, 42, 0, 100L, 42)
                == ReverseSteeringButtonPolicy.Decision.CONSUME);
    }

    @Test
    public void mappedPressTogglesOnceAndFreshDownTimeSurvivesLostUp() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 42, 0, 200L, 42)
                == ReverseSteeringButtonPolicy.Decision.TOGGLE);
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 42, 1, 200L, 42)
                == ReverseSteeringButtonPolicy.Decision.CONSUME);
        // A new physical press has a different downTime even if the prior UP was lost.
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 42, 0, 300L, 42)
                == ReverseSteeringButtonPolicy.Decision.TOGGLE);
        assertTrue(state.apply(KeyEvent.ACTION_UP, 42, 0, 200L, 42)
                == ReverseSteeringButtonPolicy.Decision.CONSUME);
        assertTrue(state.apply(KeyEvent.ACTION_UP, 42, 0, 300L, 42)
                == ReverseSteeringButtonPolicy.Decision.CONSUME);
    }

    @Test
    public void cancelStopsCaptureAndUnmappedKeysPassThrough() {
        ReverseSteeringButtonPolicy.State state = new ReverseSteeringButtonPolicy.State();
        state.beginLearning();
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 400L, -1)
                == ReverseSteeringButtonPolicy.Decision.PASS);
        state.cancel();
        assertFalse(state.isLearning());
        assertTrue(state.apply(KeyEvent.ACTION_DOWN, 43, 0, 500L, 42)
                == ReverseSteeringButtonPolicy.Decision.PASS);
    }
}
