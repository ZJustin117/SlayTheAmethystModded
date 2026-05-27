package io.stamethyst.backend.mods

import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownscaleMaterialPoliciesTest {
    @Test
    fun runtimeMaterialPolicy_defaultsAreConservative() {
        assertTrue(LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES_ENABLED)
        assertEquals(
            RuntimeTextureAtlasDownscaleQuality.P1080,
            LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES_QUALITY
        )
        assertFalse(LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_SPINE_TEXTURES_ENABLED)
        assertTrue(LauncherConfig.DEFAULT_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS_ENABLED)
    }

    @Test
    fun importOfflineDownscale_defaultsDisabled() {
        assertFalse(LauncherConfig.DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED)
        assertFalse(LauncherConfig.DEFAULT_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES_ENABLED)
        assertFalse(LauncherConfig.DEFAULT_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES_ENABLED)
        assertFalse(DEFAULT_IMPORT_DOWNSCALE_MATERIAL_POLICY.spineAtlasPages)
        assertFalse(DEFAULT_IMPORT_DOWNSCALE_MATERIAL_POLICY.ordinaryAtlasPages)
    }
}
