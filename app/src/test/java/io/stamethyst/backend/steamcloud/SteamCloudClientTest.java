package io.stamethyst.backend.steamcloud;

import in.dragonbra.javasteam.enums.EResult;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public final class SteamCloudClientTest {
    @Test
    public void ensureDirectoryExists_toleratesConcurrentCreationRace() throws Exception {
        SteamCloudClient.ensureDirectoryExists(
            new SequencedDirectoryFile("C:/steam-cloud/preferences", false, false, true),
            "output directory"
        );
    }

    @Test
    public void ensureDirectoryExists_throwsWhenDirectoryCannotBeCreated() {
        SequencedDirectoryFile directory = new SequencedDirectoryFile(
            "C:/steam-cloud/preferences",
            false,
            false,
            false
        );
        IOException error = Assert.assertThrows(
            IOException.class,
            () -> SteamCloudClient.ensureDirectoryExists(directory, "output directory")
        );

        Assert.assertEquals(
            "Failed to create output directory: " + directory.getAbsolutePath(),
            error.getMessage()
        );
    }

    @Test
    public void validateDownloadedBytes_acceptsMatchingSizeAndSha1() throws Exception {
        invokeValidateDownloadedBytes(
            "abc".getBytes(StandardCharsets.UTF_8),
            3L,
            "A9993E364706816ABA3E25717850C26C9CD0D89D",
            "%GameInstall%preferences/STSPlayer"
        );
    }

    @Test
    public void validateDownloadedBytes_throwsOnSizeMismatch() throws Exception {
        InvocationTargetException error = Assert.assertThrows(
            InvocationTargetException.class,
            () -> invokeValidateDownloadedBytes(
                "abc".getBytes(StandardCharsets.UTF_8),
                4L,
                "",
                "%GameInstall%preferences/STSPlayer"
            )
        );

        Assert.assertTrue(error.getCause() instanceof IOException);
        Assert.assertTrue(error.getCause().getMessage().contains("expectedRawSize=4 actualRawSize=3"));
    }

    @Test
    public void validateDownloadedBytes_throwsOnSha1Mismatch() throws Exception {
        InvocationTargetException error = Assert.assertThrows(
            InvocationTargetException.class,
            () -> invokeValidateDownloadedBytes(
                "abc".getBytes(StandardCharsets.UTF_8),
                3L,
                "0000000000000000000000000000000000000000",
                "%GameInstall%preferences/STSPlayer"
            )
        );

        Assert.assertTrue(error.getCause() instanceof IOException);
        Assert.assertTrue(error.getCause().getMessage().contains("Steam Cloud download SHA-1 mismatch"));
    }

    @Test
    public void isRetryableBeginHttpUploadResult_retriesTooManyPending() throws Exception {
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.TooManyPending));
        Assert.assertTrue(invokeIsRetryableBeginHttpUploadResult(EResult.Timeout));
        Assert.assertFalse(invokeIsRetryableBeginHttpUploadResult(EResult.AccessDenied));
    }

    @Test
    public void beginHttpUploadRetryDelayMs_usesLongerBackoffForTooManyPending() throws Exception {
        Assert.assertEquals(10_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 1));
        Assert.assertEquals(20_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 2));
        Assert.assertEquals(120_000L, invokeBeginHttpUploadRetryDelayMs(EResult.TooManyPending, 7));
        Assert.assertEquals(2_000L, invokeBeginHttpUploadRetryDelayMs(EResult.Timeout, 1));
        Assert.assertEquals(10_000L, invokeBeginHttpUploadRetryDelayMs(EResult.Timeout, 4));
    }

    private static void invokeValidateDownloadedBytes(
        byte[] rawBytes,
        long expectedRawSize,
        String expectedSha1,
        String remotePath
    ) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "validateDownloadedBytes",
            byte[].class,
            long.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        method.invoke(null, rawBytes, expectedRawSize, expectedSha1, remotePath);
    }

    private static boolean invokeIsRetryableBeginHttpUploadResult(EResult result) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "isRetryableBeginHttpUploadResult",
            EResult.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, result);
    }

    private static long invokeBeginHttpUploadRetryDelayMs(EResult result, int attempt) throws Exception {
        Method method = SteamCloudClient.class.getDeclaredMethod(
            "beginHttpUploadRetryDelayMs",
            EResult.class,
            int.class
        );
        method.setAccessible(true);
        return (long) method.invoke(null, result, attempt);
    }

    private static final class SequencedDirectoryFile extends File {
        private final boolean mkdirsResult;
        private final boolean[] isDirectoryResults;
        private int isDirectoryCallCount = 0;

        private SequencedDirectoryFile(String pathname, boolean mkdirsResult, boolean... isDirectoryResults) {
            super(pathname);
            this.mkdirsResult = mkdirsResult;
            this.isDirectoryResults = isDirectoryResults;
        }

        @Override
        public boolean isDirectory() {
            if (isDirectoryResults.length == 0) {
                return false;
            }
            int index = Math.min(isDirectoryCallCount, isDirectoryResults.length - 1);
            isDirectoryCallCount += 1;
            return isDirectoryResults[index];
        }

        @Override
        public boolean mkdirs() {
            return mkdirsResult;
        }
    }
}
