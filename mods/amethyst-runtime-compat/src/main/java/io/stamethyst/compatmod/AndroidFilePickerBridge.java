package io.stamethyst.compatmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

final class AndroidFilePickerBridge {
    private static final String REQUEST_PROP = "amethyst.in_game_file_picker_request";
    private static final String RESULT_PROP = "amethyst.in_game_file_picker_result";
    private static final long WAIT_TIMEOUT_MS = 120000L;
    private static final long WAIT_STEP_MS = 80L;

    private AndroidFilePickerBridge() {
    }

    static boolean isAvailable() {
        return hasNonEmptyProperty(REQUEST_PROP) && hasNonEmptyProperty(RESULT_PROP);
    }

    static File pickFile(String source, String mimeType) throws Exception {
        String requestId = Long.toString(System.currentTimeMillis()) + "-" + Long.toString(Thread.currentThread().getId());
        File requestFile = new File(System.getProperty(REQUEST_PROP));
        File resultFile = new File(System.getProperty(RESULT_PROP));
        deleteQuietly(resultFile);
        writeRequest(requestFile, requestId, mimeType, source);
        PickerResult result = waitForResult(resultFile, requestId);
        if (result == null || !"OK".equals(result.status) || result.payload == null || result.payload.length() == 0) {
            return null;
        }
        return new File(result.payload);
    }

    private static boolean hasNonEmptyProperty(String key) {
        String value = System.getProperty(key);
        return value != null && value.trim().length() > 0;
    }

    private static void writeRequest(File requestFile, String requestId, String mimeType, String source) throws Exception {
        File parent = requestFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create picker request directory");
        }
        FileWriter writer = new FileWriter(requestFile, false);
        try {
            writer.write(requestId);
            writer.write('\n');
            writer.write(normalizeMimeType(mimeType));
            writer.write('\n');
            writer.write(source == null ? "" : source);
            writer.write('\n');
        } finally {
            writer.close();
        }
    }

    private static PickerResult waitForResult(File resultFile, String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            PickerResult result = readResult(resultFile);
            if (result != null && requestId.equals(result.requestId)) {
                return result;
            }
            Thread.sleep(WAIT_STEP_MS);
        }
        return null;
    }

    private static PickerResult readResult(File resultFile) {
        if (!resultFile.isFile()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(resultFile));
            String requestId = reader.readLine();
            String status = reader.readLine();
            String payload = reader.readLine();
            if (requestId == null || status == null) {
                return null;
            }
            return new PickerResult(requestId.trim(), status.trim(), payload == null ? "" : payload.trim());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.trim().length() == 0) {
            return "*/*";
        }
        return mimeType.trim();
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.isFile()) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class PickerResult {
        final String requestId;
        final String status;
        final String payload;

        PickerResult(String requestId, String status, String payload) {
            this.requestId = requestId;
            this.status = status;
            this.payload = payload;
        }
    }
}
