package me.kylofz.fettle.optimizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal fun isOpaqueBedrockArchiveEntry(name: String): Boolean {
    val normalized = name.replace('\\', '/').lowercase(Locale.ROOT)
    return normalized.endsWith(".brarchive") &&
        (normalized.startsWith("__brarchive/") || normalized.contains("/__brarchive/"))
}

internal fun opaqueBedrockArchiveFileCount(pack: Path): Int? = ZipFile(pack.toFile()).use { zip ->
    var containsOpaqueArchive = false
    var fileCount = 0
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        fileCount++
        if (isOpaqueBedrockArchiveEntry(entry.name)) containsOpaqueArchive = true
    }
    fileCount.takeIf { containsOpaqueArchive }
}

/** Estimates raster memory for every PNG/JPEG/TGA in the archive, including font glyphs, without decoding whole images. */
public class PackAuditor {
    public fun hasOpaqueBedrockArchive(pack: Path): Boolean = opaqueBedrockArchiveFileCount(pack) != null

    public fun texturePathReferences(pack: Path): Set<String>? {
        if (!Files.isRegularFile(pack)) return null
        val paths = HashSet<String>()
        var scannedBytes = 0L
        var scannedFiles = 0
        return try {
            ZipFile(pack.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (!name.endsWith(".json") && !name.endsWith(".material")) continue
                    if (++scannedFiles > MAX_TEXTURE_REFERENCE_FILES) return null
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_ATLAS_REFERENCE_FILE_BYTES) }
                        ?: return null
                    if (scannedBytes > MAX_ATLAS_REFERENCE_TOTAL_BYTES - bytes.size) return null
                    scannedBytes += bytes.size
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    } catch (_: CharacterCodingException) {
                        return null
                    }
                    if (name.endsWith(".material") && !text.contains("textures/", ignoreCase = true)) continue
                    val strings = JsonStringScanner.read(text) ?: return null
                    for (value in strings) {
                        if (!value.contains("textures/", ignoreCase = true)) continue
                        paths += atlasTexturePath(value) ?: return null
                    }
                }
                paths
            }
        } catch (_: IOException) {
            null
        }
    }

    public fun audit(pack: Path): PackAuditReport {
        require(Files.isRegularFile(pack)) { "Pack does not exist: $pack" }

        var uncompressedBytes = 0L
        var fileCount = 0
        var estimatedTextureHandleCount = 0
        var textureFileCount = 0
        var textureMemoryBytes = 0L
        var texturesOverHighestResolutionMipBudget = 0
        var largestTexturePath: String? = null
        var largestTextureWidth = 0
        var largestTextureHeight = 0
        var largestTextureMemoryBytes = 0L
        val textureHotspots = ArrayList<PackAuditReport.TextureHotspot>(MAX_TEXTURE_HOTSPOTS)
        var largestPotentialAtlasTexturePath: String? = null
        var largestPotentialAtlasTextureMemoryBytes = 0L
        var potentialAtlasTexturesOverBudgetCount = 0
        var maxTextureDimension = 0
        var modelFileCount = 0
        var maxModelCubes = 0
        var largestModelPath: String? = null
        var maxBlockModelCubes = 0
        var blockModelFilesOver50Cubes = 0
        var unmeasuredTextureCount = 0
        val unmeasuredTextureExamples = ArrayList<String>(3)
        var oversizedModelCount = 0
        var pngAnimationMetadataCount = 0
        var rootFontGlyphCount = 0
        var rootFontGlyphTextureMemoryBytes = 0L
        var itemAtlasTextureMemoryBytes = 0L
        var blockAtlasTextureMemoryBytes = 0L
        var rootPackIconCount = 0
        var rootPackIconDimensions: Dimensions? = null
        var opaqueBedrockArchivePresent = false
        var duplicateEntryCount = 0
        val entryNames = HashSet<String>()
        var textureReferences = TextureReferenceScan(emptySet(), emptySet(), emptySet(), 0, false, false)

        ZipFile(pack.toFile()).use { zip ->
            textureReferences = findTextureReferences(zip)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (++fileCount > MAX_FILE_COUNT) throw IOException("Pack has more than $MAX_FILE_COUNT files")
                if (!entryNames.add(entry.name)) duplicateEntryCount++
                if (entry.size >= 0L) {
                    uncompressedBytes = try {
                        Math.addExact(uncompressedBytes, entry.size)
                    } catch (_: ArithmeticException) {
                        throw IOException("Pack's uncompressed size overflows a 64-bit counter")
                    }
                }

                val lowerName = entry.name.lowercase(Locale.ROOT)
                if (lowerName == "pack_icon.png") rootPackIconCount++
                if (isOpaqueBedrockArchiveEntry(entry.name)) opaqueBedrockArchivePresent = true
                if (lowerName.endsWith(".png.mcmeta")) pngAnimationMetadataCount++
                val rootFontGlyph = lowerName.startsWith("font/glyph_") && lowerName.endsWith(".png")
                if (rootFontGlyph) rootFontGlyphCount++
                if (lowerName.startsWith("models/") && lowerName.endsWith(".json")) {
                    modelFileCount++
                    val modelBytes = if (entry.size > MAX_MODEL_BYTES) null else
                        zip.getInputStream(entry).use { readBounded(it, MAX_MODEL_BYTES) }
                    if (modelBytes == null) {
                        oversizedModelCount++
                    } else {
                        val cubeCount = totalCubeCount(modelBytes.toString(Charsets.UTF_8))
                        if (cubeCount > maxModelCubes) {
                            maxModelCubes = cubeCount
                            largestModelPath = entry.name
                        }
                        if (lowerName.startsWith("models/blocks/")) {
                            maxBlockModelCubes = maxOf(maxBlockModelCubes, cubeCount)
                            if (cubeCount > 50) blockModelFilesOver50Cubes++
                        }
                    }
                }

                if (!isTexture(lowerName)) continue
                val dimensions = zip.getInputStream(entry).use { readTextureDimensions(lowerName, it) }
                if (lowerName == "pack_icon.png") {
                    rootPackIconDimensions = dimensions
                    continue
                }
                textureFileCount++
                if (dimensions == null) {
                    unmeasuredTextureCount++
                    if (unmeasuredTextureExamples.size < 3) {
                        val safePath = entry.name.filter {
                            !Character.isISOControl(it) && it != '§' && Character.getType(it) != Character.FORMAT.toInt()
                        }
                        unmeasuredTextureExamples += safePath.take(80) + if (safePath.length > 80) "…" else ""
                    }
                    continue
                }

                val imageMemoryBytes = dimensions.width.toLong() * dimensions.height.toLong() * 4L
                if (imageMemoryBytes > PackAuditReport.CREATOR_TOOLS_HIGHEST_RESOLUTION_MIP_WARNING_BYTES) {
                    texturesOverHighestResolutionMipBudget++
                }
                textureMemoryBytes = try {
                    Math.addExact(textureMemoryBytes, imageMemoryBytes)
                } catch (_: ArithmeticException) {
                    throw IOException("Texture-memory estimate overflows a 64-bit counter")
                }
                if (rootFontGlyph) {
                    rootFontGlyphTextureMemoryBytes = try {
                        Math.addExact(rootFontGlyphTextureMemoryBytes, imageMemoryBytes)
                    } catch (_: ArithmeticException) {
                        throw IOException("Root font texture-memory estimate overflows a 64-bit counter")
                    }
                }
                if (lowerName in textureReferences.atlasPaths) {
                    if (imageMemoryBytes > largestPotentialAtlasTextureMemoryBytes) {
                        largestPotentialAtlasTexturePath = entry.name
                        largestPotentialAtlasTextureMemoryBytes = imageMemoryBytes
                    }
                    if (maxOf(dimensions.width, dimensions.height) > PackAuditReport.CREATOR_TOOLS_ATLAS_MAX_DIMENSION) {
                        potentialAtlasTexturesOverBudgetCount++
                    }
                }
                if (lowerName in textureReferences.itemAtlasPaths) {
                    itemAtlasTextureMemoryBytes = try {
                        Math.addExact(itemAtlasTextureMemoryBytes, imageMemoryBytes)
                    } catch (_: ArithmeticException) {
                        throw IOException("Item-atlas texture-memory estimate overflows a 64-bit counter")
                    }
                }
                if (lowerName in textureReferences.blockAtlasPaths) {
                    blockAtlasTextureMemoryBytes = try {
                        Math.addExact(blockAtlasTextureMemoryBytes, imageMemoryBytes)
                    } catch (_: ArithmeticException) {
                        throw IOException("Block-atlas texture-memory estimate overflows a 64-bit counter")
                    }
                }
                maxTextureDimension = maxOf(maxTextureDimension, dimensions.width, dimensions.height)
                if (imageMemoryBytes > largestTextureMemoryBytes) {
                    largestTexturePath = entry.name
                    largestTextureWidth = dimensions.width
                    largestTextureHeight = dimensions.height
                    largestTextureMemoryBytes = imageMemoryBytes
                }
                recordTextureHotspot(textureHotspots, PackAuditReport.TextureHotspot(
                    entry.name, dimensions.width, dimensions.height, imageMemoryBytes
                ))
            }
        }
        estimatedTextureHandleCount = textureReferences.estimatedHandleCount
        val atlasReferenceScanComplete = textureReferences.atlasScanComplete && !opaqueBedrockArchivePresent
        val textureHandleScanComplete = textureReferences.handleScanComplete && !opaqueBedrockArchivePresent
        val textureMetricsComplete = unmeasuredTextureCount == 0 && !opaqueBedrockArchivePresent

        val warnings = TreeSet<String>()
        when {
            rootPackIconCount == 0 -> warnings += "Root pack_icon.png is missing (Creator Tools CPACKICON101)."
            rootPackIconCount > 1 -> warnings += "Pack contains multiple root pack_icon.png entries (Creator Tools CPACKICON102)."
            rootPackIconDimensions == null -> warnings += "Root pack_icon.png could not be validated as a PNG."
            rootPackIconDimensions.width != rootPackIconDimensions.height -> warnings +=
                "Root pack_icon.png is ${rootPackIconDimensions.width}x${rootPackIconDimensions.height}. Creator Tools recommends a square icon, typically 256x256 (CPACKICON104)."
        }
        if (opaqueBedrockArchivePresent) {
            warnings += "This pack contains Bedrock __brarchive data, which Fettle cannot read. Texture, atlas, handle, and file-count estimates are incomplete."
        }
        if (uncompressedBytes > 100L * MIB) warnings += "Uncompressed pack size exceeds 100 MiB."
        if (fileCount > PackAuditReport.MOJANG_HIGH_PACK_FILE_COUNT) {
            warnings += "Pack has over 10,000 files. Mojang has seen packs this large run out of memory on some devices."
        } else if (fileCount > PackAuditReport.MOJANG_SAFER_PACK_FILE_COUNT) {
            warnings += "Pack has over 7,000 files. Mojang's safer range is 5,000–7,000; keep packs under 10,000."
        }
        if (estimatedTextureHandleCount > PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE) {
            warnings += "Estimated texture handles: $estimatedTextureHandleCount. This exceeds Creator Tools' ${PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE}-handle add-on guidance."
        }
        if (textureMemoryBytes > 256L * MIB) {
            warnings += "Estimated decoded texture memory exceeds Fettle's 256 MiB warning threshold. This is not an official crash limit."
        }
        if (texturesOverHighestResolutionMipBudget > 0) {
            val examples = textureHotspots.filter {
                it.memoryBytes > PackAuditReport.CREATOR_TOOLS_HIGHEST_RESOLUTION_MIP_WARNING_BYTES
            }.take(3).joinToString { it.path }
            warnings += "$texturesOverHighestResolutionMipBudget textures exceed Creator Tools' 4 MiB highest-resolution-mip warning budget" +
                if (examples.isEmpty()) "" else " (examples: $examples)"
        }
        if (maxTextureDimension > 2_048) warnings += "A texture is larger than 2,048 px."
        if (unmeasuredTextureCount > 0) {
            val examples = unmeasuredTextureExamples.joinToString(", ").takeIf { it.isNotEmpty() }
                ?.let { " (examples: $it)" }.orEmpty()
            warnings += "$unmeasuredTextureCount textures could not be measured$examples. AUTO cannot verify the decoded texture budget."
        }
        if (oversizedModelCount > 0) warnings += "Could not scan $oversizedModelCount model files within the complexity limit."
        if (pngAnimationMetadataCount > 0) warnings += "$pngAnimationMetadataCount PNG animation metadata files keep their textures at source resolution."
        if (potentialAtlasTexturesOverBudgetCount > 0) {
            warnings += "$potentialAtlasTexturesOverBudgetCount potential atlas textures exceed Creator Tools' 256 px maximum-dimension guidance."
        }
        val estimatedItemAtlasAllocation = PackAuditReport.roundedAtlasAllocationBytes(itemAtlasTextureMemoryBytes)
        val estimatedBlockAtlasAllocation = PackAuditReport.roundedAtlasAllocationBytes(blockAtlasTextureMemoryBytes)
        if (estimatedItemAtlasAllocation > PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_WARNING_BYTES) {
            warnings += "Estimated Bedrock item-atlas allocation rounds up to $estimatedItemAtlasAllocation bytes."
        }
        if (estimatedBlockAtlasAllocation > PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_WARNING_BYTES) {
            warnings += "Estimated Bedrock block-atlas allocation rounds up to $estimatedBlockAtlasAllocation bytes."
        }
        if (estimatedItemAtlasAllocation > PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_HARD_LIMIT_BYTES ||
            estimatedBlockAtlasAllocation > PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_HARD_LIMIT_BYTES
        ) {
            warnings += "Estimated Bedrock atlas allocation exceeds Creator Tools' 256 MiB hard limit. This validator limit does not predict client crashes."
        }
        if (rootFontGlyphCount > 0) {
            warnings += "$rootFontGlyphCount root font glyph pages are outside texts/<language>/font. " +
                "$rootFontGlyphTextureMemoryBytes measured decoded bytes are counted conservatively."
        }
        if (!atlasReferenceScanComplete) {
            warnings += "Atlas references could not be fully scanned. Atlas estimates are incomplete."
        }
        if (!textureHandleScanComplete) {
            warnings += "Texture-handle references could not be fully scanned. The handle estimate is incomplete."
        }
        if (blockModelFilesOver50Cubes > 0) {
            val modelFiles = if (blockModelFilesOver50Cubes == 1) "1 block model file exceeds" else
                "$blockModelFilesOver50Cubes block model files exceed"
            warnings += "$modelFiles 50 cubes (maximum: $maxBlockModelCubes). Microsoft's warning applies to custom block models placed often."
        }
        if (duplicateEntryCount > 0) {
            warnings += "$duplicateEntryCount duplicate ZIP entry names make this pack unsupported for optimization."
        }

        return PackAuditReport(
            archiveBytes = Files.size(pack),
            uncompressedBytes = uncompressedBytes,
            fileCount = fileCount,
            estimatedTextureHandleCount = estimatedTextureHandleCount,
            textureFileCount = textureFileCount,
            textureMemoryBytes = textureMemoryBytes,
            largestTexturePath = largestTexturePath,
            largestTextureWidth = largestTextureWidth,
            largestTextureHeight = largestTextureHeight,
            largestTextureMemoryBytes = largestTextureMemoryBytes,
            largestPotentialAtlasTexturePath = largestPotentialAtlasTexturePath,
            largestPotentialAtlasTextureMemoryBytes = largestPotentialAtlasTextureMemoryBytes,
            potentialAtlasTexturesOverBudgetCount = potentialAtlasTexturesOverBudgetCount,
            maxTextureDimension = maxTextureDimension,
            modelFileCount = modelFileCount,
            maxModelCubes = maxModelCubes,
            largestModelPath = largestModelPath,
            textureMetricsComplete = textureMetricsComplete,
            atlasReferenceScanComplete = atlasReferenceScanComplete,
            textureHandleScanComplete = textureHandleScanComplete,
            unmeasuredTextureCount = unmeasuredTextureCount,
            pngAnimationMetadataCount = pngAnimationMetadataCount,
            warnings = warnings.toList(),
            textureHotspots = textureHotspots.toList(),
            oversizedModelCount = oversizedModelCount,
            rootFontGlyphCount = rootFontGlyphCount,
            rootFontGlyphTextureMemoryBytes = rootFontGlyphTextureMemoryBytes,
            itemAtlasTextureMemoryBytes = itemAtlasTextureMemoryBytes,
            blockAtlasTextureMemoryBytes = blockAtlasTextureMemoryBytes,
            opaqueBedrockArchivePresent = opaqueBedrockArchivePresent,
            optimizationUnsupported = duplicateEntryCount > 0,
        )
    }

    private fun recordTextureHotspot(
        hotspots: MutableList<PackAuditReport.TextureHotspot>,
        candidate: PackAuditReport.TextureHotspot,
    ) {
        val insertAt = hotspots.indexOfFirst { it.memoryBytes < candidate.memoryBytes }
        if (insertAt >= 0) hotspots.add(insertAt, candidate)
        else if (hotspots.size < MAX_TEXTURE_HOTSPOTS) hotspots.add(candidate)
        if (hotspots.size > MAX_TEXTURE_HOTSPOTS) hotspots.removeAt(MAX_TEXTURE_HOTSPOTS)
    }

    private fun readTextureDimensions(name: String, input: InputStream): Dimensions? {
        val prefix = input.readNBytes(18)
        if (name.endsWith(".png")) {
            if (prefix.size < 18 || !prefix.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ||
                !prefix.copyOfRange(8, 16).contentEquals(PNG_IHDR_PREFIX)
            ) return null
            val remainder = input.readNBytes(6)
            if (remainder.size != 6) return null
            val header = prefix + remainder
            return dimensions(readBigEndianInt(header, 16), readBigEndianInt(header, 20))
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return jpegDimensions(SequenceInputStream(ByteArrayInputStream(prefix), input))
        }
        if (name.endsWith(".tga")) {
            if (prefix.size < 18) return null
            val imageType = prefix[2].toInt() and 0xff
            if (imageType !in setOf(1, 2, 3, 9, 10, 11)) return null
            val pixelDepth = prefix[16].toInt() and 0xff
            if (pixelDepth !in setOf(8, 15, 16, 24, 32)) return null
            val width = (prefix[12].toInt() and 0xff) or ((prefix[13].toInt() and 0xff) shl 8)
            val height = (prefix[14].toInt() and 0xff) or ((prefix[15].toInt() and 0xff) shl 8)
            return dimensions(width, height)
        }
        return null
    }

    private fun jpegDimensions(input: InputStream): Dimensions? {
        if (input.readNBytes(2).contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())).not()) return null
        var scanned = 2
        while (scanned < MAX_JPEG_HEADER_BYTES) {
            if (input.read() != 0xff) return null
            scanned++
            var marker = input.read()
            if (marker < 0) return null
            scanned++
            while (marker == 0xff) {
                marker = input.read()
                if (marker < 0) return null
                scanned++
            }
            if (marker == 0x00) return null
            if (marker == 0xd9 || marker == 0xda) return null
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) continue

            val segmentLength = readUnsignedShort(input) ?: return null
            scanned += 2
            if (segmentLength < 2 || scanned + segmentLength - 2 > MAX_JPEG_HEADER_BYTES) return null
            if (isJpegStartOfFrame(marker)) {
                if (segmentLength < 8) return null
                if (input.read() < 0) return null // precision
                val height = readUnsignedShort(input) ?: return null
                val width = readUnsignedShort(input) ?: return null
                return dimensions(width, height)
            }
            if (!skipExactly(input, segmentLength - 2)) return null
            scanned += segmentLength - 2
        }
        return null
    }

    private fun readBounded(input: InputStream, maximumBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maximumBytes) return null
            output.write(buffer, 0, read)
        }
    }

    private fun totalCubeCount(json: String): Int {
        var total = 0
        for (match in CUBE_ARRAY_START.findAll(json)) {
            var arrayDepth = 1
            var objectDepth = 0
            var count = 0
            var inString = false
            var escaped = false
            for (index in match.range.last + 1 until json.length) {
                val character = json[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                    continue
                }
                when (character) {
                    '"' -> inString = true
                    '[' -> arrayDepth++
                    ']' -> {
                        arrayDepth--
                        if (arrayDepth == 0) break
                    }
                    '{' -> {
                        if (arrayDepth == 1 && objectDepth == 0) count++
                        objectDepth++
                    }
                    '}' -> objectDepth--
                }
            }
            if (Int.MAX_VALUE - total < count) return Int.MAX_VALUE
            total += count
        }
        return total
    }

    private fun skipExactly(input: InputStream, byteCount: Int): Boolean {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining--
            }
        }
        return true
    }

    private fun readUnsignedShort(input: InputStream): Int? {
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return null
        return (high shl 8) or low
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toLong() and 0xff) shl 24 or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)).toInt()
    }

    private fun dimensions(width: Int, height: Int): Dimensions? =
        if (width in 1..MAX_TEXTURE_DIMENSION && height in 1..MAX_TEXTURE_DIMENSION) Dimensions(width, height) else null

    private fun isJpegStartOfFrame(marker: Int): Boolean =
        marker in 0xc0..0xc3 || marker in 0xc5..0xc7 || marker in 0xc9..0xcb || marker in 0xcd..0xcf

    private fun isTexture(name: String): Boolean =
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".tga")

    private fun findTextureReferences(zip: ZipFile): TextureReferenceScan {
        val atlasPaths = HashSet<String>()
        val itemAtlasPaths = HashSet<String>()
        val blockAtlasPaths = HashSet<String>()
        val handlePaths = HashSet<String>()
        var scannedBytes = 0L
        var scannedFiles = 0
        var atlasScanComplete = true
        var handleScanComplete = true
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.lowercase(Locale.ROOT)
            val itemAtlasSource = isItemAtlasReferenceSource(name)
            val blockAtlasSource = name == "textures/terrain_texture.json"
            val atlasSource = itemAtlasSource || blockAtlasSource
            val handleSource = isTextureHandleReferenceSource(name)
            if (!atlasSource && !handleSource) continue
            if (++scannedFiles > MAX_TEXTURE_REFERENCE_FILES) return TextureReferenceScan(
                atlasPaths, itemAtlasPaths, blockAtlasPaths, handlePaths.size, false, false
            )
            val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_ATLAS_REFERENCE_FILE_BYTES) }
            if (bytes == null) {
                if (atlasSource) atlasScanComplete = false
                if (handleSource) handleScanComplete = false
                continue
            }
            if (scannedBytes > MAX_ATLAS_REFERENCE_TOTAL_BYTES - bytes.size) {
                return TextureReferenceScan(atlasPaths, itemAtlasPaths, blockAtlasPaths, handlePaths.size, false, false)
            }
            scannedBytes += bytes.size
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                if (atlasSource) atlasScanComplete = false
                if (handleSource) handleScanComplete = false
                continue
            }
            val strings = JsonStringScanner.read(text)
            if (strings == null) {
                if (atlasSource) atlasScanComplete = false
                if (handleSource) handleScanComplete = false
                continue
            }
            if (atlasSource) {
                for (value in strings) {
                    val path = atlasTexturePath(value) ?: continue
                    atlasPaths += path
                    if (itemAtlasSource) itemAtlasPaths += path
                    if (blockAtlasSource) blockAtlasPaths += path
                }
            }
            if (handleSource) strings.mapNotNullTo(handlePaths, ::textureHandlePath)
        }
        return TextureReferenceScan(atlasPaths, itemAtlasPaths, blockAtlasPaths, handlePaths.size,
            atlasScanComplete, handleScanComplete)
    }

    private fun isItemAtlasReferenceSource(name: String): Boolean =
        name == "textures/item_texture.json" || name.startsWith("attachables/") && name.endsWith(".json")

    private fun isTextureHandleReferenceSource(name: String): Boolean =
        // shortcut: path-like strings in these schemas match Creator Tools' handle sources; add schema parsing for new forms.
        name.startsWith("entity/") && name.endsWith(".json") ||
            name.startsWith("attachables/") && name.endsWith(".json") ||
            name.startsWith("particles/") && name.endsWith(".json") ||
            name.startsWith("ui/") && name.endsWith(".json") ||
            name == "textures/flipbook_textures.json"

    private fun textureHandlePath(value: String): String? {
        val raw = value.trim()
        if (!raw.startsWith("textures/", ignoreCase = true) || raw.contains('\\') || raw.split('/').any { it == ".." }) {
            return null
        }
        return raw.lowercase(Locale.ROOT)
    }

    private fun atlasTexturePath(value: String): String? {
        val raw = value.substringBefore('#').trim()
        if (raw.isEmpty() || raw.startsWith('/') || raw.contains('\\') || raw.split('/').any { it == ".." }) return null
        val path = if (raw.startsWith("textures/", ignoreCase = true)) raw else "textures/$raw"
        return when {
            path.endsWith(".png", ignoreCase = true) || path.endsWith(".jpg", ignoreCase = true) ||
                path.endsWith(".jpeg", ignoreCase = true) || path.endsWith(".tga", ignoreCase = true) ->
                path.lowercase(Locale.ROOT)
            path.substringAfterLast('/').contains('.') -> null
            else -> ("$path.png").lowercase(Locale.ROOT)
        }
    }

    private data class Dimensions(val width: Int, val height: Int)
    private data class TextureReferenceScan(
        val atlasPaths: Set<String>,
        val itemAtlasPaths: Set<String>,
        val blockAtlasPaths: Set<String>,
        val estimatedHandleCount: Int,
        val atlasScanComplete: Boolean,
        val handleScanComplete: Boolean,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val MAX_TEXTURE_REFERENCE_FILES = 10_000
        const val MAX_ATLAS_REFERENCE_FILE_BYTES = 4 * 1024 * 1024
        const val MAX_ATLAS_REFERENCE_TOTAL_BYTES = 64L * MIB
        const val MAX_FILE_COUNT = 100_000
        const val MAX_MODEL_BYTES = 4 * 1024 * 1024
        const val MAX_JPEG_HEADER_BYTES = 1024 * 1024
        const val MAX_TEXTURE_DIMENSION = 32_768
        const val MAX_TEXTURE_HOTSPOTS = 3
        val CUBE_ARRAY_START = Regex("\"cubes\"\\s*:\\s*\\[")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val PNG_IHDR_PREFIX = byteArrayOf(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
    }
}
