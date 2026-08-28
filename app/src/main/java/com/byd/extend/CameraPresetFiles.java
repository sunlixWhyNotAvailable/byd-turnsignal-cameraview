package com.byd.extend;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** The small JSON file exchanged by the native document picker and share sheet. */
final class CameraPresetFiles {
    private CameraPresetFiles() {}

    static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Не вдалося відкрити файл пресету");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) bytes.size() + count > CameraSettingsTransfer.MAX_INPUT_BYTES) {
                throw new IOException("Файл пресету перевищує 1 MiB");
            }
            bytes.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    static File write(File cacheDirectory, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CameraSettingsTransfer.MAX_INPUT_BYTES) {
            throw new IOException("Файл пресету перевищує 1 MiB");
        }
        File directory = new File(cacheDirectory, "shared_presets");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Не вдалося створити файл пресету");
        }
        File file = File.createTempFile("byd-extend-camera-preset-", ".json", directory);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException error) {
            file.delete();
            throw error;
        }
        return file;
    }
}
