package io.stamethyst.backend.workshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

internal object WorkshopPreviewCacheStore {
    private const val DIRECTORY_NAME = "workshop-preview-cache"
    private const val TARGET_SIZE_PX = 320
    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024

    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val client = OkHttpClient()

    fun load(context: Context, publishedFileId: ULong, previewUrl: String): Bitmap? {
        val cacheKey = publishedFileId.toString()
        memoryCache.get(cacheKey)?.let { return it }
        decodeCached(context, publishedFileId)?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            return bitmap
        }
        if (previewUrl.isBlank()) return null
        return download(context, publishedFileId, previewUrl)?.also { bitmap ->
            memoryCache.put(cacheKey, bitmap)
        }
    }

    fun decodeCached(context: Context, publishedFileId: ULong): Bitmap? {
        val cacheKey = publishedFileId.toString()
        memoryCache.get(cacheKey)?.let { return it }
        val file = findCacheFile(context, publishedFileId) ?: return null
        return decodeFile(file)?.also { memoryCache.put(cacheKey, it) }
    }

    fun clear(context: Context): Int {
        memoryCache.evictAll()
        val directory = cacheDirectory(context)
        if (!directory.exists()) return 0
        val deletedCount = directory.walkBottomUp()
            .filter { it.isFile }
            .count { it.delete() }
        directory.listFiles()?.filter { it.isDirectory }?.forEach { it.delete() }
        return deletedCount
    }

    private fun download(context: Context, publishedFileId: ULong, previewUrl: String): Bitmap? {
        return runCatching {
            val directory = cacheDirectory(context).apply { mkdirs() }
            val outputFile = File(directory, "${publishedFileId}.${sanitizePreviewExtension(previewUrl)}")
            val tempFile = File(directory, "${publishedFileId}.tmp")
            client.newCall(Request.Builder().url(previewUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            findCacheFile(context, publishedFileId)?.takeIf { it != outputFile }?.delete()
            if (outputFile.exists() && !outputFile.delete()) return@runCatching null
            if (!tempFile.renameTo(outputFile)) return@runCatching null
            decodeFile(outputFile)
        }.getOrNull()
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                TARGET_SIZE_PX,
                TARGET_SIZE_PX,
            )
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun findCacheFile(context: Context, publishedFileId: ULong): File? {
        return cacheDirectory(context)
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("${publishedFileId}.") &&
                    !file.name.endsWith(".tmp")
            }
            ?.firstOrNull()
    }

    private fun cacheDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun calculateInSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        while (halfWidth / sampleSize >= targetWidth && halfHeight / sampleSize >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun sanitizePreviewExtension(previewUrl: String): String {
        val path = runCatching { Request.Builder().url(previewUrl).build().url.encodedPath }
            .getOrDefault("")
        return path.substringAfterLast('.', missingDelimiterValue = "jpg")
            .substringBefore('?')
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(5)
            .ifBlank { "jpg" }
    }
}
