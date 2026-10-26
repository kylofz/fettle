package me.kylofz.fettle.optimizer

/** Resource and texture metrics used to explain low-end risk. */
public class PackAuditReport @JvmOverloads constructor(
    public val archiveBytes: Long,
    public val uncompressedBytes: Long,
    public val fileCount: Int,
    /** Number of distinct non-block texture paths found in supported runtime references. */
    public val estimatedTextureHandleCount: Int,
    public val textureFileCount: Int,
    public val textureMemoryBytes: Long,
    public val largestTexturePath: String?,
    public val largestTextureWidth: Int,
    public val largestTextureHeight: Int,
    public val largestTextureMemoryBytes: Long,
    public val largestPotentialAtlasTexturePath: String?,
    public val largestPotentialAtlasTextureMemoryBytes: Long,
    /** Potential atlas files with either dimension above the Creator Tools 256px guidance. */
    public val potentialAtlasTexturesOverBudgetCount: Int,
    public val maxTextureDimension: Int,
    public val modelFileCount: Int,
    public val maxModelCubes: Int,
    public val largestModelPath: String?,
    public val textureMetricsComplete: Boolean,
    /** True when Fettle finished scanning the supported item/terrain/attachable reference files. */
    public val atlasReferenceScanComplete: Boolean,
    /** True when Fettle finished scanning the supported entity/attachable/particle/UI/flipbook references. */
    public val textureHandleScanComplete: Boolean,
    public val unmeasuredTextureCount: Int,
    public val pngAnimationMetadataCount: Int,
    public val warnings: List<String>,
    public val textureHotspots: List<TextureHotspot> = emptyList(),
    /** Number of model files skipped because they exceeded the bounded complexity scan. */
    public val oversizedModelCount: Int = 0,
    /** Measured decoded bytes in root font glyph pages outside texts/<language>/font. */
    public val rootFontGlyphCount: Int = 0,
    public val rootFontGlyphTextureMemoryBytes: Long = 0,
    /** Raw decoded bytes referenced by the Bedrock item atlas. */
    public val itemAtlasTextureMemoryBytes: Long = 0,
    /** Raw decoded bytes referenced by the Bedrock block atlas. */
    public val blockAtlasTextureMemoryBytes: Long = 0,
    /** True when packed Bedrock resources are present but opaque to Fettle's archive scanner. */
    public val opaqueBedrockArchivePresent: Boolean = false,
    /** True when Fettle cannot safely inspect or rewrite this codec/content identity. */
    public val optimizationUnsupported: Boolean = false,
) {
    public data class TextureHotspot(
        public val path: String,
        public val width: Int,
        public val height: Int,
        public val memoryBytes: Long,
    )

    public companion object {
        public const val CREATOR_TOOLS_ATLAS_MAX_DIMENSION: Int = 256
        public const val CREATOR_TOOLS_ATLAS_MEMORY_WARNING_BYTES: Long = 64L * 1024L * 1024L
        public const val CREATOR_TOOLS_ATLAS_MEMORY_HARD_LIMIT_BYTES: Long = 256L * 1024L * 1024L
        public const val CREATOR_TOOLS_HIGHEST_RESOLUTION_MIP_WARNING_BYTES: Long = 4L * 1024L * 1024L
        public const val MOJANG_SAFER_PACK_FILE_COUNT: Int = 7_000
        public const val MOJANG_HIGH_PACK_FILE_COUNT: Int = 10_000
        public const val MINECRAFT_TEXTURE_HANDLE_LIMIT: Int = 4_000
        @Deprecated("Atlas guidance is dimension-based; use CREATOR_TOOLS_ATLAS_MAX_DIMENSION.")
        public const val CREATOR_TOOLS_ATLAS_WARNING_BYTES: Long = 256L * 1024L
        public const val CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE: Int = 800

        @JvmStatic
        public fun unsupported(reason: String): PackAuditReport = PackAuditReport(
            archiveBytes = 0,
            uncompressedBytes = 0,
            fileCount = 0,
            estimatedTextureHandleCount = 0,
            textureFileCount = 0,
            textureMemoryBytes = 0,
            largestTexturePath = null,
            largestTextureWidth = 0,
            largestTextureHeight = 0,
            largestTextureMemoryBytes = 0,
            largestPotentialAtlasTexturePath = null,
            largestPotentialAtlasTextureMemoryBytes = 0,
            potentialAtlasTexturesOverBudgetCount = 0,
            maxTextureDimension = 0,
            modelFileCount = 0,
            maxModelCubes = 0,
            largestModelPath = null,
            textureMetricsComplete = false,
            atlasReferenceScanComplete = false,
            textureHandleScanComplete = false,
            unmeasuredTextureCount = 0,
            pngAnimationMetadataCount = 0,
            warnings = listOf(reason),
            optimizationUnsupported = true,
        )

        /** Mirrors Creator Tools' atlas allocation estimate: round aggregate texture bytes up to a power of two. */
        @JvmStatic
        public fun roundedAtlasAllocationBytes(textureMemoryBytes: Long): Long {
            if (textureMemoryBytes <= 0) return 0
            if (textureMemoryBytes == 1L) return 1
            val lowerPower = java.lang.Long.highestOneBit(textureMemoryBytes - 1)
            return if (lowerPower > Long.MAX_VALUE / 2) Long.MAX_VALUE else lowerPower shl 1
        }
    }

    public val textureMemoryMiB: Double
        get() = textureMemoryBytes / (1024.0 * 1024.0)
}
