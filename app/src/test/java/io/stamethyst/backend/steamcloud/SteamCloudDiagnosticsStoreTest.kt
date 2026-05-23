package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudDiagnosticsStoreTest {
    @Test
    fun writeSummary_persistsCredentialLoginHistory() {
        val roots = TestRoots.create("steam-cloud-diagnostics-login-history")
        try {
            SteamCloudDiagnosticsStore.writeSummary(
                context = roots.context,
                operation = "credentials_login",
                outcome = "SUCCESS",
                accountName = "test-user",
                startedAtMs = 1_000L,
                completedAtMs = 2_000L,
                diagnostics = null,
                extraLines = listOf("Login detail line"),
            )

            val historyFiles = SteamCloudDiagnosticsStore.loginHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            assertEquals(1, historyFiles.size)
            assertTrue(historyFiles.single().name.startsWith("login-success-"))
            val text = historyFiles.single().readText(StandardCharsets.UTF_8)
            assertTrue(text.contains("Operation: credentials_login"))
            assertTrue(text.contains("Outcome: SUCCESS"))
            assertTrue(text.contains("Login detail line"))
            assertEquals(
                0,
                SteamCloudDiagnosticsStore.failureHistoryDir(roots.context).listFiles()?.size ?: 0
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun writeSummary_persistsFailedCredentialLoginInFailureHistory() {
        val roots = TestRoots.create("steam-cloud-diagnostics-failed-login-history")
        try {
            SteamCloudDiagnosticsStore.writeSummary(
                context = roots.context,
                operation = "credentials_login",
                outcome = "FAILED",
                accountName = "test-user",
                startedAtMs = 3_000L,
                completedAtMs = 4_000L,
                diagnostics = null,
                failureSummary = "auth failed",
            )

            val loginHistoryFiles = SteamCloudDiagnosticsStore.loginHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            val failureHistoryFiles = SteamCloudDiagnosticsStore.failureHistoryDir(roots.context)
                .listFiles()
                ?.toList()
                .orEmpty()
            assertEquals(1, loginHistoryFiles.size)
            assertEquals(1, failureHistoryFiles.size)
            assertTrue(loginHistoryFiles.single().name.startsWith("login-failed-"))
            assertTrue(failureHistoryFiles.single().name.startsWith("login-failure-"))
            assertTrue(
                failureHistoryFiles.single()
                    .readText(StandardCharsets.UTF_8)
                    .contains("Failure Summary: auth failed")
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
