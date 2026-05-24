package io.stamethyst.backend.workshop

internal object WorkshopDownloadBlocklist {
    private val blockedPublishedFileIds = setOf(
        1605060445uL,
        3658571962uL,
        1605833019uL,
        1609158507uL,
    )

    fun isBlocked(publishedFileId: ULong): Boolean = publishedFileId in blockedPublishedFileIds
}
