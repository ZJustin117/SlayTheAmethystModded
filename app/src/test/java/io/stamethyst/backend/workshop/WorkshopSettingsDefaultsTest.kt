package io.stamethyst.backend.workshop

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopSettingsDefaultsTest {
    @Test
    fun autoImportDefaultsToEnabled() {
        assertTrue(WorkshopSettingsRepository.defaultAutoImportEnabled())
    }
}
