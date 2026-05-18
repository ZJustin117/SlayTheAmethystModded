package io.stamethyst.ui.modimport

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class ModImportRequest(
    val id: Long,
    val uris: List<Uri>,
    val workshopSource: WorkshopImportSource? = null,
)

internal data class WorkshopImportSource(
    val appId: UInt,
    val publishedFileId: ULong,
)

internal object ModImportRequestBus {
    private val nextId = AtomicLong(1L)
    private val _request = MutableStateFlow<ModImportRequest?>(null)
    val request: StateFlow<ModImportRequest?> = _request

    fun requestImport(uris: List<Uri>, workshopSource: WorkshopImportSource? = null) {
        val normalized = uris.filterNotNull()
        if (normalized.isEmpty()) {
            return
        }
        _request.value = ModImportRequest(
            id = nextId.getAndIncrement(),
            uris = normalized,
            workshopSource = workshopSource,
        )
    }

    fun consume(id: Long) {
        if (_request.value?.id == id) {
            _request.value = null
        }
    }
}
