package io.stamethyst.backend.mods

data class RuntimeDownscaleMaterialPolicy(
    val ordinaryTextures: Boolean,
    val textureAtlasPages: Boolean,
    val spineTextures: Boolean,
    val offscreenFrameBuffers: Boolean
)

val DEFAULT_RUNTIME_DOWNSCALE_MATERIAL_POLICY = RuntimeDownscaleMaterialPolicy(
    ordinaryTextures = true,
    textureAtlasPages = false,
    spineTextures = false,
    offscreenFrameBuffers = true
)

data class ImportDownscaleMaterialPolicy(
    val spineAtlasPages: Boolean,
    val ordinaryAtlasPages: Boolean
)

val DEFAULT_IMPORT_DOWNSCALE_MATERIAL_POLICY = ImportDownscaleMaterialPolicy(
    spineAtlasPages = true,
    ordinaryAtlasPages = false
)
