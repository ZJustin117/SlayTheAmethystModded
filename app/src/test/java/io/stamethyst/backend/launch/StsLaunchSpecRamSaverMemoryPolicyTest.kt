package io.stamethyst.backend.launch

import io.stamethyst.config.GpuResourceGuardianMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StsLaunchSpecRamSaverMemoryPolicyTest {
    @Test
    fun resolveTexturePressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true
            )
        )
    }

    @Test
    fun resolveTexturePressureDownscaleEnabled_preservesConfiguredValueWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveTexturePressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false
            )
        )
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_disablesWhenRamSaverEnabled() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = true,
                configuredMode = GpuResourceGuardianMode.ULTRA_AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveGpuResourceGuardianModeForLaunch_preservesConfiguredModeWithoutRamSaver() {
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            StsLaunchSpec.resolveGpuResourceGuardianModeForLaunch(
                ramSaverEnabled = false,
                configuredMode = GpuResourceGuardianMode.AGGRESSIVE
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_disablesWhenRamSaverEnabled() {
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = true,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
    }

    @Test
    fun resolveFboPressureDownscaleEnabled_requiresConfigAndMaterialPolicyWithoutRamSaver() {
        assertTrue(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = false,
                offscreenFrameBuffersEnabled = true
            )
        )
        assertFalse(
            StsLaunchSpec.resolveFboPressureDownscaleEnabled(
                ramSaverEnabled = false,
                configuredEnabled = true,
                offscreenFrameBuffersEnabled = false
            )
        )
    }
}
