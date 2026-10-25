package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CameraFrameMailboxTest {
    @Test public void preparationReservationLeavesPendingFrameAvailable() {
        CameraFrameMailbox mailbox = new CameraFrameMailbox();

        int pending = mailbox.reserveFree(0);
        mailbox.publish(pending);

        assertEquals(-1, mailbox.reserveFree(pending));
        int prepared = mailbox.reserveFree(1);
        assertNotEquals(pending, prepared);
        mailbox.cancel(prepared);
        assertEquals(prepared, mailbox.reserveFree(prepared));
        mailbox.cancel(prepared);

        assertEquals(pending, mailbox.acquire());
        assertEquals(-1, mailbox.reserveFree(pending));
        mailbox.release(pending);

        mailbox.close();
        assertEquals(-1, mailbox.reserveFree(0));
        assertEquals(-1, mailbox.reserveFree(1));
    }
}
