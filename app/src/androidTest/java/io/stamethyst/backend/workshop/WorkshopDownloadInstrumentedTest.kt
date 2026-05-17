package io.stamethyst.backend.workshop

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadInstrumentedTest {
    @Test
    fun downloadsPublishedWorkshopItemToPrivateDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = WorkshopService(context)
        val appId = 646570u
        val publishedFileId = 3725840622uL
        val outputDir = File(context.filesDir, "workshop-test/$appId/$publishedFileId").apply {
            deleteRecursively()
            mkdirs()
        }
        val details = service.getDetails(appId, publishedFileId)
        val events = mutableListOf<WorkshopDownloadEvent>()

        service.download(WorkshopDownloadRequest(details, outputDir)).collect { event ->
            events += event
        }

        val files = outputDir.walkTopDown().filter { it.isFile }.toList()
        val diagnostics = buildString {
            appendLine("events=")
            events.forEach { appendLine(it.toString()) }
            appendLine("files=")
            files.forEach { appendLine("${it.relativeTo(outputDir).path} ${it.length()}") }
        }

        assertTrue(diagnostics, events.any { it is WorkshopDownloadEvent.Completed })
        assertTrue(diagnostics, files.any { it.extension.equals("jar", ignoreCase = true) })
    }
}
