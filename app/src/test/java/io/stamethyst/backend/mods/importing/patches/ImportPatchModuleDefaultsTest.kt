package io.stamethyst.backend.mods.importing.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchModuleDefaultsTest {
    @Test
    fun basePlan_usesModuleDefaultEnablement() {
        val atlasDownscalePlan = AtlasOfflineDownscalePatchModule.basePlan(applicable = true)
        assertTrue(atlasDownscalePlan.defaultEnabled)
        assertTrue(atlasDownscalePlan.userConfigurable)

        val duplicateEntryPlan = DuplicateZipEntryPatchModule.basePlan(applicable = true)
        assertTrue(duplicateEntryPlan.defaultEnabled)
        assertFalse(duplicateEntryPlan.userConfigurable)
    }

    @Test
    fun atlasDownscale_isOnlyInteractivePatchModule() {
        val modules = listOf(
            DuplicateZipEntryPatchModule,
            ManifestRootPatchModule,
            AtlasFilterPatchModule,
            AtlasOfflineDownscalePatchModule,
            FrierenImportPatchModule,
            DownfallImportPatchModule,
            VupShionImportPatchModule,
            JacketNoAnoKoImportPatchModule,
        )

        assertEquals(
            listOf(AtlasOfflineDownscalePatchModule.id),
            modules.filter { it.userConfigurable }.map { it.id }
        )
    }
}
