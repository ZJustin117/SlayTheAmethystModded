package io.stamethyst.model

import androidx.compose.runtime.Stable

@Stable
data class ModItemUi(
    val modId: String,
    val manifestModId: String,
    val storagePath: String,
    val name: String,
    val version: String,
    val description: String,
    val dependencies: List<String>,
    val required: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val explicitPriority: Int?,
    val effectivePriority: Int?,
    val importPatchDetails: String? = null,
    val newlyImported: Boolean = false,
    val favorite: Boolean = false,
    val workshop: WorkshopModUi? = null,
    val alias: String = ""
)

@Stable
data class WorkshopModUi(
    val appId: UInt,
    val publishedFileId: ULong,
    val state: WorkshopModState,
    val statusText: String = "",
    val localJarPath: String = "",
    val localPreviewImagePath: String = "",
    val downloadProgressPercent: Int? = null,
)

enum class WorkshopModState {
    ImportedUnpatched,
    ImportedPatched,
    Downloading,
    DownloadPaused,
    DownloadFailed,
    NonStandardDownloaded,
    TexturePackInstalled,
    UpdateAvailable,
    FileMissing
}
