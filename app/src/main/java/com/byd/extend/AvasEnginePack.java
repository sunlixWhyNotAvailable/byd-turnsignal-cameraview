package com.byd.extend;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** One predecoded, allowlisted engine recording pack. */
public final class AvasEnginePack {
    public static final int SAMPLE_RATE = 48_000;

    public static final class Layer {
        public final int rpm;
        public final float[] on;
        public final float[] off;

        private Layer(int rpm, float[] on, float[] off) {
            this.rpm = rpm;
            this.on = on;
            this.off = off;
        }
    }

    interface AssetReader {
        byte[] read(String relativeName) throws IOException;
    }

    public final String id;
    public final String name;
    public final int idleRpm;
    public final int maxRpm;
    public final int sampleRate;
    public final float[] idle;
    public final float[] start;
    public final float[] stop;
    public final Layer[] layers;

    private AvasEnginePack(String id, String name, int idleRpm, int maxRpm,
            float[] idle, float[] start, float[] stop, Layer[] layers) {
        this.id = id;
        this.name = name;
        this.idleRpm = idleRpm;
        this.maxRpm = maxRpm;
        this.sampleRate = SAMPLE_RATE;
        this.idle = idle;
        this.start = start;
        this.stop = stop;
        this.layers = layers;
    }

    /** Loads only the requested built-in pack. */
    public static AvasEnginePack load(AssetManager assets, String id) throws IOException {
        if (assets == null) throw new IllegalArgumentException("assets are required");
        requireAllowedId(id);
        String directory = "avas_engines/" + id + "/";
        byte[] manifest;
        try (InputStream input = assets.open(directory + "manifest.json")) {
            manifest = readAll(input);
        }
        return fromManifest(id, new String(manifest, StandardCharsets.UTF_8), name -> {
            try (InputStream input = assets.open(directory + name)) {
                return readAll(input);
            }
        });
    }

    static AvasEnginePack fromManifest(String requestedId, String manifestText,
            AssetReader reader) throws IOException {
        requireAllowedId(requestedId);
        if (manifestText == null || reader == null) throw new IllegalArgumentException();
        try {
            JSONObject manifest = new JSONObject(manifestText);
            String id = manifest.getString("id");
            if (!requestedId.equals(id)) throw new IOException("engine manifest ID mismatch");
            String name = manifest.getString("name").trim();
            int idleRpm = manifest.getInt("idleRpm");
            int maxRpm = manifest.getInt("maxRpm");
            if (name.isEmpty() || idleRpm <= 0 || maxRpm <= idleRpm || maxRpm > 20_000
                    || manifest.getInt("sampleRate") != SAMPLE_RATE) {
                throw new IOException("invalid engine pack metadata");
            }

            float[] idle = decodeWav(reader.read(assetName(manifest, "idle")));
            float[] start = decodeWav(reader.read(assetName(manifest, "start")));
            float[] stop = decodeWav(reader.read(assetName(manifest, "stop")));
            JSONArray sourceLayers = manifest.getJSONArray("layers");
            if (sourceLayers.length() == 0 || sourceLayers.length() > 16) {
                throw new IOException("invalid engine layer count");
            }
            Layer[] layers = new Layer[sourceLayers.length()];
            int previousRpm = 0;
            for (int index = 0; index < layers.length; index++) {
                JSONObject source = sourceLayers.getJSONObject(index);
                int rpm = source.getInt("rpm");
                if (rpm < idleRpm || rpm > maxRpm || rpm <= previousRpm) {
                    throw new IOException("engine layers must have ascending RPM values");
                }
                layers[index] = new Layer(rpm,
                        decodeWav(reader.read(assetName(source, "on"))),
                        decodeWav(reader.read(assetName(source, "off"))));
                previousRpm = rpm;
            }
            return new AvasEnginePack(id, name, idleRpm, maxRpm, idle, start, stop, layers);
        } catch (JSONException exception) {
            throw new IOException("invalid engine manifest", exception);
        }
    }

    private static void requireAllowedId(String id) {
        if (!"ferrari_v8".equals(id) && !"jaguar_v6".equals(id)
                && !"huracan_v10".equals(id) && !"german_l4".equals(id)) {
            throw new IllegalArgumentException("unsupported engine pack");
        }
    }

    private static String assetName(JSONObject object, String key)
            throws JSONException, IOException {
        String name = object.getString(key);
        if (name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.contains("..") || !name.endsWith(".wav")) {
            throw new IOException("invalid engine asset name");
        }
        return name;
    }

    private static float[] decodeWav(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12 || !ascii(bytes, 0, "RIFF")
                || !ascii(bytes, 8, "WAVE")) {
            throw new IOException("invalid engine WAV");
        }
        long riffEnd = uint32(bytes, 4) + 8L;
        if (riffEnd < 12 || riffEnd > bytes.length) throw new IOException("truncated engine WAV");
        int format = -1;
        int channels = -1;
        long sampleRate = -1;
        long byteRate = -1;
        int blockAlign = -1;
        int bits = -1;
        int dataOffset = -1;
        int dataLength = -1;
        long cursor = 12;
        while (cursor + 8 <= riffEnd) {
            int chunkOffset = (int) cursor;
            long chunkLength = uint32(bytes, chunkOffset + 4);
            long chunkStart = cursor + 8;
            long chunkEnd = chunkStart + chunkLength;
            if (chunkEnd < chunkStart || chunkEnd > riffEnd) {
                throw new IOException("truncated engine WAV chunk");
            }
            if (ascii(bytes, chunkOffset, "fmt ")) {
                if (chunkLength < 16 || format >= 0) throw new IOException("invalid engine WAV format");
                format = uint16(bytes, (int) chunkStart);
                channels = uint16(bytes, (int) chunkStart + 2);
                sampleRate = uint32(bytes, (int) chunkStart + 4);
                byteRate = uint32(bytes, (int) chunkStart + 8);
                blockAlign = uint16(bytes, (int) chunkStart + 12);
                bits = uint16(bytes, (int) chunkStart + 14);
            } else if (ascii(bytes, chunkOffset, "data")) {
                if (dataOffset >= 0) throw new IOException("duplicate engine WAV data");
                if (chunkLength > Integer.MAX_VALUE) throw new IOException("engine WAV is too large");
                dataOffset = (int) chunkStart;
                dataLength = (int) chunkLength;
            }
            cursor = chunkEnd + (chunkLength & 1L);
        }
        if (format != 1 || channels != 1 || sampleRate != SAMPLE_RATE
                || byteRate != SAMPLE_RATE * 2L || blockAlign != 2 || bits != 16
                || dataOffset < 0 || dataLength <= 0 || (dataLength & 1) != 0) {
            throw new IOException("engine WAV must be mono PCM16 at 48 kHz");
        }
        float[] samples = new float[dataLength / 2];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) uint16(bytes, dataOffset + index * 2) / 32768.0f;
        }
        return samples;
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != (byte) value.charAt(index)) return false;
        }
        return true;
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
