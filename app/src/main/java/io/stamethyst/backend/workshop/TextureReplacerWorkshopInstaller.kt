package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object TextureReplacerWorkshopInstaller {
    fun install(context: Context, details: WorkshopItemDetails, sourceDir: File): File {
        if (!sourceDir.isDirectory) {
            throw IOException("Missing workshop content directory: ${sourceDir.absolutePath}")
        }
        val targetDir = RuntimePaths.texturePackDir(context, details.summary.publishedFileId)
        replaceDirectory(sourceDir, targetDir)
        ensurePackEnabled(
            file = RuntimePaths.textureReplacerPackOrderFile(context),
            id = details.summary.publishedFileId.toString(),
            name = details.summary.title.ifBlank { "Workshop ${details.summary.publishedFileId}" }
        )
        return targetDir
    }

    private fun replaceDirectory(sourceDir: File, targetDir: File) {
        val parent = targetDir.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create texture pack parent directory: ${parent.absolutePath}")
        }
        val tempDir = File(parent, "${targetDir.name}.tmp-${System.currentTimeMillis()}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        if (!tempDir.mkdirs()) {
            throw IOException("Failed to create texture pack staging directory: ${tempDir.absolutePath}")
        }
        try {
            sourceDir.copyRecursively(tempDir, overwrite = true)
            if (targetDir.exists() && !targetDir.deleteRecursively()) {
                throw IOException("Failed to replace existing texture pack: ${targetDir.absolutePath}")
            }
            if (!tempDir.renameTo(targetDir)) {
                throw IOException("Failed to finalize texture pack install: ${targetDir.absolutePath}")
            }
        } catch (error: Throwable) {
            tempDir.deleteRecursively()
            throw error
        }
    }

    private fun ensurePackEnabled(file: File, id: String, name: String) {
        file.parentFile?.mkdirs()
        val existing = readPackOrder(file)
        val updated = JSONArray()
        var found = false
        for (index in 0 until existing.length()) {
            val item = existing.optJSONObject(index) ?: continue
            if (item.optString("id") == id) {
                found = true
                updated.put(
                    JSONObject(item.toString())
                        .put("id", id)
                        .put("name", item.optString("name").ifBlank { name })
                )
            } else {
                updated.put(item)
            }
        }
        if (!found) {
            updated.put(
                JSONObject()
                    .put("enabled", true)
                    .put("id", id)
                    .put("name", name)
            )
        }
        writeAtomically(file, updated.toString(2))
    }

    private fun readPackOrder(file: File): JSONArray {
        if (!file.isFile) return JSONArray()
        return runCatching {
            JSONArray(file.readText(StandardCharsets.UTF_8))
        }.getOrDefault(JSONArray())
    }

    private fun writeAtomically(file: File, text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create config directory: ${parent.absolutePath}")
        }
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(text, StandardCharsets.UTF_8)
        if (file.exists() && !file.delete()) {
            throw IOException("Failed to replace config file: ${file.absolutePath}")
        }
        if (!temp.renameTo(file)) {
            throw IOException("Failed to write config file: ${file.absolutePath}")
        }
    }
}
