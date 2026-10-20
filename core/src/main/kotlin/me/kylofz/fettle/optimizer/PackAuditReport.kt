package me.kylofz.fettle.optimizer

/** Resource and texture metrics used to explain low-end risk. */
public class PackAuditReport(
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
) {
    public companion object {
        public const val CREATOR_TOOLS_ATLAS_MAX_DIMENSION: Int = 256
        @Deprecated("Atlas guidance is dimension-based; use CREATOR_TOOLS_ATLAS_MAX_DIMENSION.")
        public const val CREATOR_TOOLS_ATLAS_WARNING_BYTES: Long = 256L * 1024L
        public const val CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE: Int = 800
    }

    public val textureMemoryMiB: Double
        get() = textureMemoryBytes / (1024.0 * 1024.0)
}
