package com.byd.turnsignalguard.capture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class AdbPacket {
    static final int A_CNXN = command("CNXN");
    static final int A_AUTH = command("AUTH");
    static final int A_OPEN = command("OPEN");
    static final int A_OKAY = command("OKAY");
    static final int A_CLSE = command("CLSE");
    static final int A_WRTE = command("WRTE");

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int VERSION = 0x01000001;
    static final int MAX_DATA = 262144;
    static final int MAX_PAYLOAD = 1024 * 1024;

    final int command;
    final int arg0;
    final int arg1;
    final byte[] payload;

    AdbPacket(int command, int arg0, int arg1, byte[] payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload == null ? new byte[0] : payload;
    }

    static int command(String ascii) {
        if (ascii == null || ascii.length() != 4) {
            throw new IllegalArgumentException("ADB command must be four ASCII chars");
        }
        return ascii.charAt(0)
                | (ascii.charAt(1) << 8)
                | (ascii.charAt(2) << 16)
                | (ascii.charAt(3) << 24);
    }

    static int checksum(byte[] payload) {
        int checksum = 0;
        if (payload != null) {
            for (byte value : payload) {
                checksum += value & 0xff;
            }
        }
        return checksum;
    }

    static void write(OutputStream out, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        writeIntLe(out, command);
        writeIntLe(out, arg0);
        writeIntLe(out, arg1);
        writeIntLe(out, safePayload.length);
        writeIntLe(out, checksum(safePayload));
        writeIntLe(out, command ^ 0xffffffff);
        if (safePayload.length > 0) {
            out.write(safePayload);
        }
        out.flush();
    }

    static AdbPacket read(InputStream in) throws IOException {
        byte[] header = new byte[24];
        readFully(in, header);
        int command = intLe(header, 0);
        int arg0 = intLe(header, 4);
        int arg1 = intLe(header, 8);
        int payloadLength = intLe(header, 12);
        intLe(header, 16);
        intLe(header, 20);
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new IOException("ADB packet payload length out of range: " + payloadLength);
        }
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            readFully(in, payload);
        }
        return new AdbPacket(command, arg0, arg1, payload);
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("ADB stream closed");
            }
            offset += read;
        }
    }

    private static int intLe(byte[] buffer, int offset) {
        return (buffer[offset] & 0xff)
                | ((buffer[offset + 1] & 0xff) << 8)
                | ((buffer[offset + 2] & 0xff) << 16)
                | ((buffer[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLe(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }
}
