package io.stamethyst.backend.workshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadBlocklistTest {
    @Test
    fun blocksLauncherManagedWorkshopItems() {
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1605060445uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(3658571962uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1605833019uL))
        assertTrue(WorkshopDownloadBlocklist.isBlocked(1609158507uL))
        assertFalse(WorkshopDownloadBlocklist.isBlocked(123456789uL))
    }
}
