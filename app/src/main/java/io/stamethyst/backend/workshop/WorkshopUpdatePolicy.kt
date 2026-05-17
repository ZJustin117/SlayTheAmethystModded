package io.stamethyst.backend.workshop

internal object WorkshopUpdatePolicy {
    fun hasUpdate(localUpdatedAtMillis: Long, remoteUpdatedAtMillis: Long): Boolean =
        remoteUpdatedAtMillis > localUpdatedAtMillis
}
