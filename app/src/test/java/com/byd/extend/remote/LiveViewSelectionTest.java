package com.byd.extend.remote;

import org.junit.Test;

import static org.junit.Assert.*;

public class LiveViewSelectionTest {
    @Test public void selectionsKeepOneStreamAndRejectLateCommandsUntilArchiveStartsANewOne() {
        LiveViewSelection selection = new LiveViewSelection();

        String first = selection.select(1);
        assertNotNull(first);
        assertEquals(first, selection.select(2));
        assertEquals(first, selection.select(5));
        assertNull(selection.select(4));
        assertEquals(5, selection.revision());
        assertEquals(first, selection.streamId());

        assertTrue(selection.archive(6));
        assertFalse(selection.archive(5));
        assertNull(selection.select(5));
        String resumed = selection.select(7);
        assertNotNull(resumed);
        assertNotEquals(first, resumed);
        assertEquals(resumed, selection.ensureStreamId());
    }

    @Test public void closingAnEncoderGivesAutomaticResumeANewMediaIdentity() {
        LiveViewSelection selection = new LiveViewSelection();
        String first = selection.select(1);

        selection.closeStream();

        assertEquals(1, selection.revision());
        assertNotEquals(first, selection.ensureStreamId());
        assertEquals(1, selection.revision());
    }
}
