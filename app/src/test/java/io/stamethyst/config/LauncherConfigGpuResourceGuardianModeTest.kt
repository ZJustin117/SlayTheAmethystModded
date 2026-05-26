package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigGpuResourceGuardianModeTest {
    @Test
    fun gpuResourceGuardian_highMemoryDefaultOff() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(13L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun gpuResourceGuardian_veryLowMemoryDefaultOff() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(8L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun gpuResourceGuardian_lowMemoryDefaultOff() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(9L * 1024L * 1024L * 1024L)
        )
        assertEquals(
            GpuResourceGuardianMode.OFF,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(12L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun gpuResourceGuardian_unknownMemoryDefaultOff() {
        assertEquals(
            GpuResourceGuardianMode.OFF,
            LauncherConfig.resolveDefaultGpuResourceGuardianMode(-1L)
        )
    }

    @Test
    fun fromPersistedValue_acceptsKnownModes() {
        assertEquals(GpuResourceGuardianMode.OFF, GpuResourceGuardianMode.fromPersistedValue("off"))
        assertEquals(GpuResourceGuardianMode.SAFE, GpuResourceGuardianMode.fromPersistedValue("safe"))
        assertEquals(
            GpuResourceGuardianMode.AGGRESSIVE,
            GpuResourceGuardianMode.fromPersistedValue("aggressive")
        )
        assertEquals(
            GpuResourceGuardianMode.ULTRA_AGGRESSIVE,
            GpuResourceGuardianMode.fromPersistedValue("ultra_aggressive")
        )
        assertEquals(GpuResourceGuardianMode.LEGACY, GpuResourceGuardianMode.fromPersistedValue("legacy"))
    }

    @Test
    fun runtimePropertyValue_mapsLegacyToOff() {
        assertEquals("off", GpuResourceGuardianMode.LEGACY.runtimePropertyValue)
        assertEquals("safe", GpuResourceGuardianMode.SAFE.runtimePropertyValue)
        assertEquals("ultra_aggressive", GpuResourceGuardianMode.ULTRA_AGGRESSIVE.runtimePropertyValue)
    }

    @Test
    fun fromPersistedValue_rejectsUnknownModes() {
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(null))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue(""))
        assertEquals(null, GpuResourceGuardianMode.fromPersistedValue("unknown"))
    }
}
