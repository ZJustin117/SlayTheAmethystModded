package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class OptionalModStorageCoordinatorTest {
    @Test
    fun migrateLegacyOptionalMods_movesOptionalJarsIntoLibraryAndRewritesConfigs() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-migration-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val libraryDir = Files.createDirectory(tempDir.resolve("mods_library")).toFile()
        val enabledModsConfig = tempDir.resolve("enabled_mods.txt").toFile()
        val priorityModsConfig = tempDir.resolve("priority_mod_roots.txt").toFile()

        Files.write(runtimeModsDir.toPath().resolve("BaseMod.jar"), byteArrayOf(1))
        Files.write(runtimeModsDir.toPath().resolve("StSLib.jar"), byteArrayOf(2))
        Files.write(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar"), byteArrayOf(5))
        Files.write(runtimeModsDir.toPath().resolve("RamSaver.jar"), byteArrayOf(6))
        val firstOptional = Files.write(runtimeModsDir.toPath().resolve("Alpha.jar"), byteArrayOf(3)).toFile()
        val secondOptional = Files.write(runtimeModsDir.toPath().resolve("Beta.jar"), byteArrayOf(4)).toFile()

        enabledModsConfig.writeText(
            listOf(firstOptional.absolutePath, "alpha", secondOptional.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )
        priorityModsConfig.writeText(
            secondOptional.absolutePath,
            StandardCharsets.UTF_8
        )

        OptionalModStorageCoordinator.migrateLegacyOptionalMods(
            legacyRuntimeModsDir = runtimeModsDir,
            libraryDir = libraryDir,
            enabledModsConfig = enabledModsConfig,
            priorityModsConfig = priorityModsConfig,
            normalizeSelectionPath = { it }
        )

        assertFalse(firstOptional.exists())
        assertFalse(secondOptional.exists())
        assertTrue(runtimeModsDir.toPath().resolve("BaseMod.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("StSLib.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("RamSaver.jar").toFile().isFile)

        val migratedFirst = libraryDir.toPath().resolve("Alpha.jar").toFile()
        val migratedSecond = libraryDir.toPath().resolve("Beta.jar").toFile()
        assertTrue(migratedFirst.isFile)
        assertTrue(migratedSecond.isFile)
        assertEquals(
            listOf(migratedFirst.absolutePath, "alpha", migratedSecond.absolutePath),
            enabledModsConfig.readLines(StandardCharsets.UTF_8)
        )
        assertEquals(
            migratedSecond.absolutePath,
            priorityModsConfig.readText(StandardCharsets.UTF_8).trim()
        )
    }

    @Test
    fun syncRuntimeOptionalMods_keepsReservedAndMirrorsEnabledLibraryFilesOnly() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-sync-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val libraryDir = Files.createDirectory(tempDir.resolve("mods_library")).toFile()

        Files.write(runtimeModsDir.toPath().resolve("BaseMod.jar"), byteArrayOf(1))
        Files.write(runtimeModsDir.toPath().resolve("StSLib.jar"), byteArrayOf(2))
        Files.write(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar"), byteArrayOf(10))
        Files.write(runtimeModsDir.toPath().resolve("RamSaver.jar"), byteArrayOf(11))
        Files.write(runtimeModsDir.toPath().resolve("stale.jar"), byteArrayOf(9))
        val runtimeAlpha = Files.write(runtimeModsDir.toPath().resolve("Alpha.jar"), byteArrayOf(0)).toFile()

        val libraryAlpha = Files.write(libraryDir.toPath().resolve("Alpha.jar"), byteArrayOf(3, 4, 5)).toFile()
        val libraryBeta = Files.write(libraryDir.toPath().resolve("Beta.jar"), byteArrayOf(6, 7, 8)).toFile()

        OptionalModStorageCoordinator.syncRuntimeOptionalMods(
            runtimeModsDir = runtimeModsDir,
            enabledLibraryFiles = listOf(libraryAlpha, libraryBeta)
        )

        assertTrue(runtimeModsDir.toPath().resolve("BaseMod.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("StSLib.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("RamSaver.jar").toFile().isFile)
        assertFalse(runtimeModsDir.toPath().resolve("stale.jar").toFile().exists())
        assertTrue(runtimeModsDir.toPath().resolve("Alpha.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("Beta.jar").toFile().isFile)
        assertArrayEquals(
            Files.readAllBytes(libraryAlpha.toPath()),
            Files.readAllBytes(runtimeAlpha.toPath())
        )
        assertArrayEquals(
            Files.readAllBytes(libraryBeta.toPath()),
            Files.readAllBytes(runtimeModsDir.toPath().resolve("Beta.jar"))
        )
    }

    @Test
    fun ensureOptionalModLibraryReady_keepsCommittedImportTargetWhenMarkerStale() {
        val roots = TestRoots.create("optional-mod-storage-interrupted-import-test")
        val libraryDir = RuntimePaths.optionalModsLibraryDir(roots.context).apply { mkdirs() }
        val target = Files.write(libraryDir.toPath().resolve("HalfImported.jar"), byteArrayOf(1, 2, 3)).toFile()
        val marker = libraryDir.toPath().resolve(".HalfImported.jar.importing.marker").toFile()
        marker.writeText(target.name, StandardCharsets.UTF_8)
        val scratch = Files.write(libraryDir.toPath().resolve(".HalfImported.jar.123.importing"), byteArrayOf(9)).toFile()

        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(roots.context)

        assertTrue(target.exists())
        assertFalse(marker.exists())
        assertFalse(scratch.exists())
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context
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
