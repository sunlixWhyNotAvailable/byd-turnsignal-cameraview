package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ReverseSteeringRuntimeTest {
    @Test public void decodesSignedBigEndianTenthsAndUnsignedTimestamp() {
        ReverseSteeringRuntime.SteeringPacket positive = ReverseSteeringRuntime.decode(
                new byte[]{0x01, (byte) 0xf4, 0x01, 0x23, 0x45, 0x67});
        ReverseSteeringRuntime.SteeringPacket negative = ReverseSteeringRuntime.decode(
                new byte[]{(byte) 0xfe, 0x0c, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef});

        assertEquals(50.0f, positive.angleDegrees, 0.0f);
        assertEquals(0x01234567L, positive.timestamp);
        assertEquals(-50.0f, negative.angleDegrees, 0.0f);
        assertEquals(0x89abcdefL, negative.timestamp);
    }

    @Test public void rejectsPayloadsThatDoNotContainTheFixedSixByteValue() {
        try {
            ReverseSteeringRuntime.decode(new byte[]{0x00, 0x01, 0x02, 0x03});
            fail("expected malformed steering timestamp rejection");
        } catch (IllegalArgumentException expected) {
            // The setting payload includes two angle bytes and four timestamp bytes.
        }
    }
}
