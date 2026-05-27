package io.stamethyst.backend.mods

data class RuntimeDownscaleMaterialPolicy(
    val ordinaryTextures: Boolean,
    val textureAtlasPages: RuntimeTextureAtlasDownscaleQuality,
    val spineTextures: Boolean,
    val offscreenFrameBuffers: Boolean
)

enum class RuntimeTextureAtlasDownscaleQuality(
    val prefValue: String,
    val maxPixels: Long,
    val maxEdge: Int
) {
    P720("720p", 1280L * 720L, 1280),
    P1080("1080p", 1920L * 1080L, 1920),
    P2K("2k", 2560L * 1440L, 2560),
    NATIVE("native", Long.MAX_VALUE, Int.MAX_VALUE);

    val enabled: Boolean
        get() = this != NATIVE

    companion object {
        fun fromPrefValue(value: String?): RuntimeTextureAtlasDownscaleQuality {
            return entries.firstOrNull { it.prefValue == value } ?: P1080
        }
    }
}

val DEFAULT_RUNTIME_DOWNSCALE_MATERIAL_POLICY = RuntimeDownscaleMaterialPolicy(
    ordinaryTextures = true,
    textureAtlasPages = RuntimeTextureAtlasDownscaleQuality.P1080,
    spineTextures = false,
    offscreenFrameBuffers = true
)

data class ImportDownscaleMaterialPolicy(
    val spineAtlasPages: Boolean,
    val ordinaryAtlasPages: Boolean
)

val DEFAULT_IMPORT_DOWNSCALE_MATERIAL_POLICY = ImportDownscaleMaterialPolicy(
    spineAtlasPages = false,
    ordinaryAtlasPages = false
)
