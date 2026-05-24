package io.stamethyst.backend.diag

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.workshop.WorkshopAutoImportPatchLogStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveBuilderAutoImportPatchLogsTest {
    @Test
    fun writeWorkshopAutoImportPatchLogsForArchive_includesPatchLogs() {
        val roots = TestRoots.create("diag-auto-import-patch-logs")
        val logFile = WorkshopAutoImportPatchLogStore.createLogFile(roots.context)
        WorkshopAutoImportPatchLogStore.appendLine(logFile, "修补完成：module=mod.downfall.mobile_layout")
        val archive = File(roots.rootDir, "diagnostics.zip")

        FileOutputStream(archive, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                DiagnosticsArchiveBuilder.writeWorkshopAutoImportPatchLogsForArchive(zipOutput, roots.context)
            }
        }

        ZipFile(archive).use { zipFile ->
            val indexEntry = zipFile.getEntry("sts/workshop/auto_import_patch_logs/index.txt")
            assertNotNull(indexEntry)
            val logEntry = zipFile.getEntry("sts/workshop/auto_import_patch_logs/${logFile.name}")
            assertNotNull(logEntry)
            val logText = zipFile.getInputStream(logEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(logText.contains("mod.downfall.mobile_layout"))
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
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }
                    }
                )
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
