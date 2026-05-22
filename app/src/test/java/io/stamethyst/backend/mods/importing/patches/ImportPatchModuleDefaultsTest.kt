package io.stamethyst.backend.mods.importing.patches

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchModuleDefaultsTest {
    @Test
    fun basePlan_usesModuleDefaultEnablement() {
        val atlasDownscalePlan = AtlasOfflineDownscalePatchModule.basePlan(applicable = true)
        assertFalse(atlasDownscalePlan.defaultEnabled)
        assertTrue(atlasDownscalePlan.userConfigurable)

        val duplicateEntryPlan = DuplicateZipEntryPatchModule.basePlan(applicable = true)
        assertTrue(duplicateEntryPlan.defaultEnabled)
        assertFalse(duplicateEntryPlan.userConfigurable)
    }
}
