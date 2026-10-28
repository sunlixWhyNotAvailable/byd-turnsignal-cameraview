package com.byd.extend;

import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Exercises the actual attachment wire payload; no OEM/Binder completion is substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class RemoteExteriorAttachmentTest {
    @Test public void fallbackAcknowledgementRetainsCauseAndRequiresExteriorRecovery() {
        String reference = "0123456789abcdef0123456789abcdef";
        RemoteExteriorAttachment parsed = roundTrip(new RemoteExteriorAttachment(false, reference));
        assertFalse(parsed.attached);
        assertEquals(reference, parsed.errorReference);
        for (int view : new int[]{RemoteCameraView.COMBINED, RemoteCameraView.COMPOSITION,
                RemoteCameraView.FRONT, RemoteCameraView.REAR, RemoteCameraView.LEFT, RemoteCameraView.RIGHT})
            assertTrue(parsed.needsRecovery(view));
        for (int view : new int[]{RemoteCameraView.CABIN, RemoteCameraView.DRIVER, RemoteCameraView.INTERIOR})
            assertFalse(parsed.needsRecovery(view));
    }

    @Test public void attachedAcknowledgementStopsRecovery() {
        RemoteExteriorAttachment parsed = roundTrip(new RemoteExteriorAttachment(true, null));
        assertTrue(parsed.attached);
        assertNull(parsed.errorReference);
        assertFalse(parsed.needsRecovery(RemoteCameraView.COMBINED));
        assertFalse(parsed.needsRecovery(RemoteCameraView.COMPOSITION));
    }

    @Test public void pendingPreparationDoesNotBecomeAttachedWithoutAnError() {
        RemoteExteriorAttachment parsed = roundTrip(new RemoteExteriorAttachment(false, null));
        assertFalse(parsed.attached);
        assertNull(parsed.errorReference);
        assertTrue(parsed.needsRecovery(RemoteCameraView.COMBINED));
    }

    @Test public void rejectsMissingTruncatedAndInvalidAcknowledgements() {
        Parcel reply = Parcel.obtain();
        try {
            assertThrows(IllegalArgumentException.class, () -> RemoteExteriorAttachment.readFromParcel(reply));
            reply.writeInt(1);
            reply.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> RemoteExteriorAttachment.readFromParcel(reply));
            reply.setDataSize(0);
            reply.setDataPosition(0);
            reply.writeInt(2);
            reply.writeString(null);
            reply.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> RemoteExteriorAttachment.readFromParcel(reply));
        } finally { reply.recycle(); }
    }

    @Test public void rejectsSuccessWithFailureReferenceAndUnboundedReference() {
        for (String reference : new String[]{"0123456789abcdef0123456789abcdef", "x".repeat(1024)}) {
            Parcel reply = Parcel.obtain();
            try {
                reply.writeInt(reference.length() == 32 ? 1 : 0);
                reply.writeString(reference);
                reply.setDataPosition(0);
                assertThrows(IllegalArgumentException.class, () -> RemoteExteriorAttachment.readFromParcel(reply));
            } finally { reply.recycle(); }
        }
    }

    private static RemoteExteriorAttachment roundTrip(RemoteExteriorAttachment source) {
        Parcel reply = Parcel.obtain();
        try {
            source.writeToParcel(reply);
            reply.setDataPosition(0);
            RemoteExteriorAttachment parsed = RemoteExteriorAttachment.readFromParcel(reply);
            assertEquals(0, reply.dataAvail());
            return parsed;
        } finally { reply.recycle(); }
    }
}
