package io.stamethyst.backend.workshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopUpdatePolicyTest {
    @Test
    fun reportsUpdateOnlyWhenRemoteTimestampIsNewer() {
        assertTrue(WorkshopUpdatePolicy.hasUpdate(localUpdatedAtMillis = 100L, remoteUpdatedAtMillis = 101L))
        assertFalse(WorkshopUpdatePolicy.hasUpdate(localUpdatedAtMillis = 100L, remoteUpdatedAtMillis = 100L))
        assertFalse(WorkshopUpdatePolicy.hasUpdate(localUpdatedAtMillis = 100L, remoteUpdatedAtMillis = 99L))
    }
}
