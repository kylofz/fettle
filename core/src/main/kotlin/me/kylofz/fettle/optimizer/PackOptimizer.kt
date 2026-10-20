package me.kylofz.fettle.optimizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HashSet
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.floor

internal fun packSetRewriteParallelism(
    packCount: Int,
    outputsAreDistinct: Boolean,
    availableProcessors: Int,
    maxHeapBytes: Long,
): Int = if (packCount > 1 && outputsAreDistinct && availableProcessors >= 4 &&
    maxHeapBytes >= 3L * 1024L * 1024L * 1024L
) 2 else 1

/**
 * Resource-pack rewrite. Normal optimization prunes unreachable assets and
 * trims proven-unused entity texture margins; MINI/SAFE also resize textures.
 */
public class PackOptimizer {
    private data class GeometryInfo(
        val path: String,
        val identifier: String,
        val width: Int,
        val height: Int,
        val minU: Double,
        val minV: Double,
        val maxU: Double,
        val maxV: Double,
    )

    private data class OptimizationPlan(
        val unreachableEntries: Set<String> = emptySet(),
        val protectedEntries: Set<String> = emptySet(),
        val textureCrops: Map<String, TextureCrop> = emptyMap(),
        val geometryTransforms: Map<String, GeometryTransform> = emptyMap(),
        val expectedTextureDimensions: Map<String, Pair<Int, Int>> = emptyMap(),
        val jsonStringRewrites: Map<String, String> = emptyMap(),
        val generatedEntries: Map<String, ByteArray> = emptyMap(),
        val animatedTextures: Set<String> = emptySet(),
        val unsafeGeometryTextures: Set<String> = emptySet(),
        val unsafeGeometryFiles: Set<String> = emptySet(),
        val disableTextureResizing: Boolean = false,
        val disableGeometryTransforms: Boolean = false,
        val disableLocalPruning: Boolean = false,
    )

    private data class GeometryTransform(
        val width: Int,
        val height: Int,
        val uScale: Double,
        val vScale: Double,
        val uOffset: Double,
        val vOffset: Double,
    )

    private data class UvCropPlan(val crop: TextureCrop)

    private data class TexturePathUse(
        val jsonPath: String,
        val value: String,
        val rawLiteral: Boolean,
        val directItemIcon: Boolean,
    )

    private data class TextureDedupPlan(
        val removed: Set<String> = emptySet(),
        val jsonStringRewrites: Map<String, String> = emptyMap(),
    )

    private data class UvAnimationScan(
        val textureAliasesByController: Map<String, Set<String>?>,
        val geometryTextureAliasesByController: Map<String, List<GeometryTextureAliases>?>,
        val complete: Boolean,
    )

    private data class GeometryTextureAliases(val geometry: String, val texture: String)

    private data class PackEntryKey(val packIndex: Int, val path: String)

    private data class PackSetTextureUse(val consumerPackIndex: Int, val value: String)

    private data class PackSetTextureReference(
        val consumerPackIndex: Int,
        val consumerEntryPath: String,
        val value: String,
    )

    private data class PackSetTextureDedupPlan(
        val removedEntries: List<Set<String>>,
        val jsonStringRewrites: List<Map<String, String>>,
    )

    private data class PackSetGeometryDedupPlan(
        val removedEntries: List<Set<String>>,
        val jsonStringRewrites: List<Map<String, String>>,
    )

    private data class AtlasComponent(
        val models: List<Pair<PackEntryKey, GeometryInfo>>,
        val textures: List<PackEntryKey>,
        val variantPages: List<List<PackEntryKey>>,
        val variantHashes: List<String>,
        val crop: TextureCrop,
    ) {
        val variantCount: Int get() = variantPages.size
        val width: Int get() = crop.width + ATLAS_GUTTER * 2
        val height: Int get() = crop.height + ATLAS_GUTTER * 2
        val signature: String get() = "${crop.width}x${crop.height}|${variantHashes.joinToString("|")}"
    }

    private data class AtlasSlot(val components: List<AtlasComponent>) {
        val component: AtlasComponent get() = components.first()
        val variantCount: Int get() = component.variantCount
        val width: Int get() = component.width
        val height: Int get() = component.height
        val signature: String get() = component.signature
    }

    private data class AtlasPlacement(val slot: AtlasSlot, val x: Int, val y: Int)

    private class AtlasBin {
        val placements = ArrayList<AtlasPlacement>()
        var cursorX = 0
            private set
        var cursorY = 0
            private set
        var rowHeight = 0
            private set
        var usedWidth = 0
            private set
        var usedHeight = 0
            private set

        fun place(slot: AtlasSlot): Boolean {
            if (slot.width > MAX_ATLAS_DIMENSION || slot.height > MAX_ATLAS_DIMENSION) return false
            var x = cursorX
            var y = cursorY
            var nextRowHeight = rowHeight
            if (x + slot.width > MAX_ATLAS_DIMENSION) {
                x = 0
                y += rowHeight
                nextRowHeight = 0
            }
            if (y + slot.height > MAX_ATLAS_DIMENSION) return false
            placements += AtlasPlacement(slot, x, y)
            cursorX = x + slot.width
            cursorY = y
            rowHeight = maxOf(nextRowHeight, slot.height)
            usedWidth = maxOf(usedWidth, cursorX)
            usedHeight = maxOf(usedHeight, y + slot.height)
            return true
        }
    }

    private data class PackSetAttachment(
        val packIndex: Int,
        val entryPath: String,
        val geometry: Map<String, String>,
        val textures: Map<String, String>,
        val materials: Map<String, String>?,
        val renderControllers: Set<String>,
    )

    public fun optimize(input: Path, output: Path): PackOptimizationReport =
        optimizeInternal(input, output, emptyList(), null, optimizePng = false, maxTextureDimension = 0, pruneUnusedAssets = true)

    /** Lossless rewrite that preserves unchanged PNG bytes and crops only proven-unused static UV areas. */
    public fun optimizeForLowEnd(input: Path, output: Path): PackOptimizationReport {
        val linkedPlan = findPackSetCropPlans(listOf(input), 0, cropToUvExtent = true).single()
            .takeUnless { it.disableLocalPruning }
        return optimizeInternal(
            input, output, emptyList(), null, optimizePng = false, maxTextureDimension = 0,
            pruneUnusedAssets = true, extraPlan = linkedPlan,
        )
    }

    /** Applies lossless optimization to a linked pack set so shared geometry and split texture frames stay aligned. */
    public fun optimizeForLowEndSet(
        inputs: List<Path>,
        outputs: List<Path>,
        maxTextureDimension: Int = 0,
    ): List<PackOptimizationReport> = rewritePackSet(
        inputs, outputs, maxTextureDimension, cropToUvExtent = true, pruneUnusedAssets = true
    )

    /** Resizes a previously lossless pack set without repeating geometry cropping or pruning. */
    public fun optimizeResizedPackSet(
        inputs: List<Path>,
        outputs: List<Path>,
        maxTextureDimension: Int,
    ): List<PackOptimizationReport> = rewritePackSet(
        inputs, outputs, maxTextureDimension, cropToUvExtent = false, pruneUnusedAssets = false
    )

    private fun rewritePackSet(
        inputs: List<Path>,
        outputs: List<Path>,
        maxTextureDimension: Int,
        cropToUvExtent: Boolean,
        pruneUnusedAssets: Boolean,
    ): List<PackOptimizationReport> {
        require(inputs.isNotEmpty()) { "At least one input pack is required" }
        require(inputs.size == outputs.size) { "Each input pack needs one output path" }
        require(maxTextureDimension >= 0) { "Maximum texture dimension cannot be negative" }
        if (inputs.size == 1) {
            val input = inputs.single()
            val output = outputs.single()
            if (pruneUnusedAssets) return listOf(
                if (maxTextureDimension == 0) optimizeForLowEnd(input, output)
                else optimizeMini(input, output, emptyList(), maxTextureDimension)
            )
            val plan = findPackSetCropPlans(inputs, maxTextureDimension, cropToUvExtent).single()
            return listOf(optimizeInternal(input, output, emptyList(), null, optimizePng = true,
                    maxTextureDimension = maxTextureDimension, pruneUnusedAssets = false,
                    extraPlan = plan, onlyResizeAboveLimit = true))
        }

        val setPlans = findPackSetCropPlans(inputs, maxTextureDimension, cropToUvExtent)
        val rewrites = inputs.indices.map { index -> {
            optimizeInternal(
                inputs[index], outputs[index], emptyList(), null, optimizePng = maxTextureDimension > 0,
                maxTextureDimension = maxTextureDimension,
                pruneUnusedAssets = pruneUnusedAssets, extraPlan = setPlans[index],
                onlyResizeAboveLimit = maxTextureDimension > 0
            )
        } }
        val runtime = Runtime.getRuntime()
        val parallelism = packSetRewriteParallelism(
            inputs.size,
            outputs.map { it.toAbsolutePath().normalize() }.toSet().size == outputs.size,
            runtime.availableProcessors(),
            runtime.maxMemory(),
        )
        if (parallelism == 1) return rewrites.map { it() }

        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = rewrites.map { rewrite -> CompletableFuture.supplyAsync(rewrite, executor) }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            return futures.map { it.join() }
        } catch (failure: CompletionException) {
            val cause = failure.cause ?: failure
            if (cause is Exception) throw cause
            if (cause is Error) throw cause
            throw failure
        } finally {
            executor.shutdown()
        }
    }

    public fun linkedPackGroups(inputs: List<Path>): List<Set<Int>> {
        if (inputs.size !in 2..MAX_PACK_SET_FILES || inputs.any { !Files.isRegularFile(it) }) return emptyList()
        val zips = ArrayList<ZipFile>(inputs.size)
        try {
            inputs.forEach { zips += ZipFile(it.toFile()) }
            val geometryPacks = HashMap<String, MutableSet<Int>>()
            val textureEntrySizes = HashMap<PackEntryKey, Long>()
            val textureKeysByPath = HashMap<String, MutableList<PackEntryKey>>()
            val attachments = ArrayList<PackSetAttachment>()
            val geometryTextureAliasesByController = HashMap<String, List<GeometryTextureAliases>?>()
            var scannedBytes = 0L

            for (packIndex in zips.indices) {
                val zip = zips[packIndex]
                mergeControllerAliases(
                    geometryTextureAliasesByController,
                    findUvAnimationControllers(zip).geometryTextureAliasesByController,
                )
                val entries = zip.entries()
                var entriesScanned = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (++entriesScanned > MAX_ZIP_ENTRIES) return emptyList()
                    val name = entry.name
                    if (name.endsWith(".png", ignoreCase = true)) {
                        val key = PackEntryKey(packIndex, name)
                        textureEntrySizes[key] = entry.size
                        textureKeysByPath.getOrPut(name, ::ArrayList).add(key)
                        continue
                    }
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    if (entry.size > MAX_JSON_BYTES) return emptyList()
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) } ?: return emptyList()
                    scannedBytes += bytes.size
                    if (scannedBytes > MAX_PACK_SET_SCAN_BYTES) return emptyList()
                    val text = decodeUtf8(bytes) ?: return emptyList()
                    if (JsonStringScanner.read(text) == null) return emptyList()

                    if (name.startsWith(ENTITY_GEOMETRY_PATH) && name.endsWith(".geo.json", ignoreCase = true)) {
                        GEOMETRY_IDENTIFIER.findAll(text).forEach { match ->
                            geometryPacks.getOrPut(match.groupValues[1], ::HashSet).add(packIndex)
                        }
                    } else if (name.startsWith("attachables/") || name.startsWith("entity/")) {
                        val geometryMap = simpleStringMap(text, "geometry")
                        val textureMap = simpleStringMap(text, "textures")
                        if (geometryMap != null && textureMap != null) {
                            attachments += PackSetAttachment(
                                packIndex, name, geometryMap, textureMap, simpleStringMap(text, "materials"),
                                RENDER_CONTROLLER_REFERENCE.findAll(text).map { it.groupValues[1] }.toSet(),
                            )
                        }
                    }
                }
            }

            val neighbors = List(inputs.size) { HashSet<Int>() }
            for (attachment in attachments) {
                val links = linkedGeometryTextures(
                    attachment.geometry, attachment.textures, attachment.materials,
                    attachment.renderControllers, geometryTextureAliasesByController,
                )
                val geometryPackIndices: Set<Int>
                val texturePackIndices: Set<Int>
                if (links == null) {
                    geometryPackIndices = attachment.geometry.values.flatMap { geometryPacks[it].orEmpty() }.toSet()
                    texturePackIndices = attachment.textures.values.flatMap { value ->
                        val texture = textureEntryName(value) ?: return@flatMap emptyList()
                        val local = PackEntryKey(attachment.packIndex, texture)
                        if (local in textureEntrySizes) listOf(local.packIndex)
                        else textureKeysByPath[texture].orEmpty().map { it.packIndex }
                    }.toSet()
                } else {
                    geometryPackIndices = links.flatMap { (identifier, _) ->
                        geometryPacks[identifier].orEmpty()
                    }.toSet()
                    texturePackIndices = links.mapNotNull { (_, value) ->
                        val texture = textureEntryName(value) ?: return@mapNotNull null
                        resolvePackSetTexture(
                            attachment.packIndex, texture, textureEntrySizes, textureKeysByPath,
                        )?.packIndex
                    }.toSet()
                }
                val linkedPacks = geometryPackIndices + texturePackIndices + attachment.packIndex
                for (pack in linkedPacks) {
                    for (neighbor in linkedPacks) {
                        if (pack != neighbor) neighbors[pack] += neighbor
                    }
                }
            }

            val groups = ArrayList<Set<Int>>()
            val visited = HashSet<Int>()
            for (start in inputs.indices) {
                if (start in visited || neighbors[start].isEmpty()) continue
                val pending = ArrayDeque<Int>()
                val group = LinkedHashSet<Int>()
                pending += start
                while (pending.isNotEmpty()) {
                    val current = pending.removeFirst()
                    if (!visited.add(current)) continue
                    group += current
                    neighbors[current].forEach { if (it !in visited) pending += it }
                }
                if (group.size > 1) groups += group.toSet()
            }
            return groups.toList()
        } catch (_: IOException) {
            return emptyList()
        } finally {
            zips.forEach { zip ->
                try {
                    zip.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /** Explicit visual trade-off profile for low-memory clients. */
    public fun optimizeMini(
        input: Path,
        output: Path,
        keepPatterns: Collection<String>,
        maxTextureDimension: Int,
    ): PackOptimizationReport = optimizeInternal(
        input, output, keepPatterns, null, optimizePng = true, maxTextureDimension = maxTextureDimension,
        pruneUnusedAssets = keepPatterns.isEmpty()
    )

    /** Keeps pack content and downsizes textures for low-memory clients. */
    public fun optimizeSafe(
        input: Path,
        output: Path,
        maxTextureDimension: Int,
    ): PackOptimizationReport = optimizeMini(input, output, emptyList(), maxTextureDimension)

    /**
     * Rewrites a pack while retaining the manifest and files whose paths contain one
     * of [keepPatterns]. A non-null [manifestJson] replaces the source manifest.
     */
    public fun optimizeKeeping(
        input: Path,
        output: Path,
        keepPatterns: Collection<String>,
        manifestJson: String?,
    ): PackOptimizationReport = optimizeInternal(
        input, output, keepPatterns, manifestJson, optimizePng = false, maxTextureDimension = 0,
        pruneUnusedAssets = false
    )

    private fun optimizeInternal(
        input: Path,
        output: Path,
        keepPatterns: Collection<String>,
        manifestJson: String?,
        optimizePng: Boolean,
        maxTextureDimension: Int,
        pruneUnusedAssets: Boolean,
        extraPlan: OptimizationPlan? = null,
        onlyResizeAboveLimit: Boolean = false,
    ): PackOptimizationReport {
        require(Files.isRegularFile(input)) { "Input pack does not exist: $input" }

        val inputBytes = Files.size(input)
        val parent = output.toAbsolutePath().parent
            ?: throw IllegalArgumentException("Output must have a parent directory: $output")
        Files.createDirectories(parent)

        val patterns = keepPatterns.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toList()
        val overrideManifest = manifestJson?.toByteArray(StandardCharsets.UTF_8)
            ?.let(JsonMinifier::minifyIfSmaller)
        val animatedTextures = findAnimatedTextures(input)
        val localPlan = if (pruneUnusedAssets && patterns.isEmpty()) {
            findOptimizationPlan(input, animatedTextures, maxTextureDimension, allowDirectTextureDedup = extraPlan == null)
        } else OptimizationPlan()
        val plan = if (extraPlan == null) localPlan else localPlan.copy(
            unreachableEntries = if (extraPlan.disableLocalPruning) emptySet()
                else (localPlan.unreachableEntries - extraPlan.protectedEntries) + extraPlan.unreachableEntries,
            protectedEntries = localPlan.protectedEntries + extraPlan.protectedEntries,
            textureCrops = (if (extraPlan.disableGeometryTransforms) emptyMap() else localPlan.textureCrops + extraPlan.textureCrops)
                .filterKeys { it !in extraPlan.unsafeGeometryTextures },
            geometryTransforms = (if (extraPlan.disableGeometryTransforms) emptyMap()
                else localPlan.geometryTransforms + extraPlan.geometryTransforms)
                .filterKeys { it !in extraPlan.unsafeGeometryFiles },
            expectedTextureDimensions = if (extraPlan.disableGeometryTransforms) emptyMap()
                else (localPlan.expectedTextureDimensions + extraPlan.expectedTextureDimensions)
                    .filterKeys { it !in extraPlan.unsafeGeometryTextures },
            jsonStringRewrites = if (extraPlan.disableLocalPruning) emptyMap()
                else localPlan.jsonStringRewrites + extraPlan.jsonStringRewrites,
            generatedEntries = localPlan.generatedEntries + extraPlan.generatedEntries,
            unsafeGeometryTextures = localPlan.unsafeGeometryTextures + extraPlan.unsafeGeometryTextures,
            unsafeGeometryFiles = localPlan.unsafeGeometryFiles + extraPlan.unsafeGeometryFiles,
            disableTextureResizing = localPlan.disableTextureResizing || extraPlan.disableTextureResizing,
        )
        val temp = Files.createTempFile(parent, ".${output.fileName}", ".tmp")

        var entriesRead = 0
        var zipEntriesSeen = 0
        var entriesWritten = 0
        var entriesFiltered = 0
        var jsonEntriesMinified = 0
        var pngEntriesOptimized = 0
        var otherRasterEntriesResized = 0
        val inputNames = HashSet<String>()
        val outputNames = HashSet<String>()

        try {
            BoundedZipInputStream(Files.newInputStream(input)).use { zipIn ->
                RawZipBuilder(temp).use { zipOut ->
                    fun addEntry(name: String, entryInput: InputStream, sourceMethod: Int? = null) {
                        if (name.endsWith(".png", ignoreCase = true) && sourceMethod == ZipEntry.STORED) {
                            zipOut.addStored(name, entryInput)
                        } else {
                            zipOut.add(name, entryInput)
                        }
                    }

                    if (overrideManifest != null) {
                        zipOut.add("manifest.json", ByteArrayInputStream(overrideManifest))
                        outputNames += "manifest.json"
                        entriesWritten++
                    }

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zipIn.nextEntry ?: break
                        if (++zipEntriesSeen > MAX_ZIP_ENTRIES) throw IOException("Pack has more than $MAX_ZIP_ENTRIES ZIP entries")
                        if (entry.isDirectory) {
                            zipIn.closeEntry()
                            continue
                        }

                        entriesRead++
                        val name = validateEntryName(entry.name)
                        if (!inputNames.add(name)) {
                            throw IOException("Duplicate ZIP entry: $name")
                        }

                        if (name == "manifest.json" && overrideManifest != null) {
                            drain(zipIn, buffer)
                            zipIn.closeEntry()
                            continue
                        }

                        val keep = name !in plan.unreachableEntries && (name == "manifest.json" || patterns.isEmpty() ||
                            patterns.any { name.lowercase(Locale.ROOT).contains(it) })
                        if (!keep) {
                            entriesFiltered++
                            drain(zipIn, buffer)
                            zipIn.closeEntry()
                            continue
                        }

                        if (!outputNames.add(name)) {
                            throw IOException("Duplicate output ZIP entry: $name")
                        }
                        val isPng = name.endsWith(".png", ignoreCase = true)
                        // Whole-image scaling preserves normalized UVs; unresolved links only forbid coupled crops.
                        val resizePng = isPng && name !in animatedTextures &&
                            (maxTextureDimension <= 0 || !plan.disableTextureResizing &&
                                (name !in plan.unsafeGeometryTextures || name !in plan.textureCrops)) &&
                            (optimizePng || name in plan.textureCrops)
                        val isOtherRaster = maxTextureDimension > 0 && isOtherRaster(name)
                        val entryTooLargeForTransform = entry.size >= 0L &&
                            ((name.endsWith(".json", ignoreCase = true) && entry.size > MAX_JSON_BYTES) ||
                                (isPng && entry.size > MAX_PNG_BYTES) ||
                                (isOtherRaster && entry.size > MAX_OTHER_RASTER_BYTES))
                        if (name.endsWith(".json", ignoreCase = true)) {
                            if (entryTooLargeForTransform) {
                                addEntry(name, zipIn)
                            } else {
                                val original = readBounded(zipIn, MAX_JSON_BYTES)
                                    ?: throw IOException("JSON entry exceeds the bounded transform size: $name")
                                val remapped = rewriteTextureReferences(original, plan.jsonStringRewrites)
                                val resized = plan.geometryTransforms[name]?.let { updateGeometry(remapped, it) } ?: remapped
                                val compact = JsonMinifier.minifyIfSmaller(resized)
                                if (!compact.contentEquals(original)) jsonEntriesMinified++
                                zipOut.add(name, ByteArrayInputStream(compact))
                            }
                        } else if (resizePng || isOtherRaster) {
                            if (entryTooLargeForTransform) {
                                addEntry(name, zipIn, entry.method)
                            } else {
                                val limit = if (isPng) MAX_PNG_BYTES else MAX_OTHER_RASTER_BYTES
                                val original = readBounded(zipIn, limit)
                                    ?: throw IOException("Raster entry exceeds the bounded transform size: $name")
                                val optimized = if (isPng) {
                                    val crop = plan.textureCrops[name]
                                    val cropped = if (crop == null) original else PngOptimizer.cropRegion(original, crop)
                                        ?: throw IOException("Could not safely crop texture: $name")
                                    val fontGlyphAtlas = isFontGlyphAtlas(name) && name !in plan.expectedTextureDimensions
                                    val textureDimensionLimit = if (fontGlyphAtlas && maxTextureDimension > 0) {
                                        maxOf(maxTextureDimension, MIN_FONT_GLYPH_TEXTURE_DIMENSION)
                                    } else {
                                        maxTextureDimension
                                    }
                                    if (optimizePng && !(onlyResizeAboveLimit && crop == null &&
                                            PngOptimizer.dimensions(original)?.let { maxOf(it.first, it.second) <= textureDimensionLimit } == true)
                                    ) PngOptimizer.optimizeIfSmaller(
                                        cropped, textureDimensionLimit, fontGlyphAtlas = fontGlyphAtlas
                                    ) else cropped
                                } else {
                                    if (name.endsWith(".tga", ignoreCase = true)) {
                                        RasterOptimizer.resizeTga(original, maxTextureDimension)
                                    } else {
                                        RasterOptimizer.resizeJpeg(original, maxTextureDimension)
                                    }
                                }
                                val expectedDimensions = plan.expectedTextureDimensions[name]
                                if (expectedDimensions != null && PngOptimizer.dimensions(optimized) != expectedDimensions) {
                                    throw IOException("Texture transform did not match its geometry plan: $name")
                                }
                                if (!optimized.contentEquals(original)) {
                                    if (isPng) pngEntriesOptimized++ else otherRasterEntriesResized++
                                }
                                if (isPng) zipOut.addStored(name, optimized)
                                else addEntry(name, ByteArrayInputStream(optimized))
                            }
                        } else {
                            addEntry(name, zipIn, entry.method)
                        }
                        zipIn.closeEntry()
                        entriesWritten++
                    }
                    for ((name, bytes) in plan.generatedEntries) {
                        val validated = validateEntryName(name)
                        if (!outputNames.add(validated)) throw IOException("Duplicate generated ZIP entry: $validated")
                        zipOut.addStored(validated, bytes)
                        entriesWritten++
                    }
                    zipOut.finish()
                }
            }

            moveIntoPlace(temp, output)
        } catch (throwable: Throwable) {
            Files.deleteIfExists(temp)
            throw throwable
        }

        return PackOptimizationReport(
            inputBytes = inputBytes,
            outputBytes = Files.size(output),
            entriesRead = entriesRead,
            entriesWritten = entriesWritten,
            entriesFiltered = entriesFiltered,
            jsonEntriesMinified = jsonEntriesMinified,
            pngEntriesOptimized = pngEntriesOptimized,
            otherRasterEntriesResized = otherRasterEntriesResized,
        )
    }

    /** Plans only JSON-proven dead assets and unambiguous static cube-UV crops. */
    private fun findOptimizationPlan(
        input: Path,
        animatedTextures: Set<String>,
        maxTextureDimension: Int,
        allowDirectTextureDedup: Boolean,
    ): OptimizationPlan {
        val geometryIdsByPath = HashMap<String, Set<String>>()
        val geometryInfoById = HashMap<String, MutableList<GeometryInfo>>()
        val references = HashSet<String>()
        val textureReferenceTokens = ArrayList<String>()
        val stitchedTextures = ArrayList<String>()
        val textureEntrySizes = HashMap<String, Long>()
        val geometriesByTexture = HashMap<String, MutableSet<String>>()
        val texturesByGeometry = HashMap<String, MutableSet<String>>()
        val directTextureReferences = HashMap<String, Int>()
        val directTexturePathUses = HashMap<String, MutableList<String>>()
        val textureMapPathUses = HashMap<String, MutableList<String>>()
        val itemTexturePathUses = HashMap<String, MutableList<TexturePathUse>>()
        val animatedTextureReferences = HashSet<String>()
        val unsafeGeometryTextures = HashSet<String>()
        val unsafeGeometryFiles = HashSet<String>()
        var scannedBytes = 0L
        var hasUnclassifiedUvAnimation = false
        // shortcut: material references are not schema-validated, so skip destructive pruning when they occur.
        var hasUnresolvedAssetReferences = false

        try {
            ZipFile(input.toFile()).use { zip ->
                val uvAnimationScan = findUvAnimationControllers(zip)
                if (!uvAnimationScan.complete) hasUnclassifiedUvAnimation = true
                val entries = zip.entries()
                var entriesScanned = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (++entriesScanned > MAX_ZIP_ENTRIES) return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
                    val name = entry.name
                    if (name.endsWith(".png", ignoreCase = true) &&
                        (name.startsWith(STITCHED_TEXTURE_PATH, ignoreCase = true) ||
                            name.substringAfterLast('/').endsWith("_stitched.png", ignoreCase = true))
                    ) {
                        stitchedTextures += name
                    }
                    if (name.endsWith(".png", ignoreCase = true)) textureEntrySizes[name] = entry.size
                    if (name.endsWith(".material", ignoreCase = true)) {
                        val material = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) }
                        val materialText = material?.let { bytes ->
                            try {
                                StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(bytes))
                                    .toString()
                            } catch (_: CharacterCodingException) {
                                null
                            }
                        }
                        val materialStrings = materialText?.let(JsonStringScanner::read)
                        if (materialText == null || materialStrings == null) {
                            hasUnresolvedAssetReferences = true
                        } else {
                            if (materialText.contains("\"uv_anim\"")) hasUnclassifiedUvAnimation = true
                            val referencesAssets = materialText.contains("textures/") || materialText.contains("geometry.")
                            if (referencesAssets) hasUnresolvedAssetReferences = true
                            references.addAll(materialStrings.filter(::isAssetReference))
                            textureReferenceTokens.addAll(materialStrings.filter { it.contains("textures/") })
                        }
                    }
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    if (entry.size > MAX_JSON_BYTES) return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)

                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) }
                        ?: return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
                    scannedBytes += bytes.size
                    if (scannedBytes > MAX_PRUNE_SCAN_BYTES) return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    } catch (_: CharacterCodingException) {
                        return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
                    }
                    val strings = JsonStringScanner.read(text)
                        ?: return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
                    if (text.contains("\"uv_anim\"") && !name.startsWith(RENDER_CONTROLLERS_PATH, ignoreCase = true)) {
                        hasUnclassifiedUvAnimation = true
                    }
                    val directItemIconCounts = HashMap<String, Int>()
                    if (name == ITEM_TEXTURE_JSON_PATH) {
                        ITEM_TEXTURE_TEXTURE_VALUE.findAll(text).forEach { match ->
                            val value = match.groupValues[1]
                            directItemIconCounts[value] = (directItemIconCounts[value] ?: 0) + 1
                        }
                    }
                    for (value in strings) {
                        if (value.contains("textures/")) textureReferenceTokens += value
                        val texture = textureEntryName(value) ?: continue
                        if (texture.startsWith(JAVA_RENDERED_ITEM_PATH)) {
                            val directItemIcon = (directItemIconCounts[value] ?: 0) > 0
                            if (directItemIcon) directItemIconCounts[value] = directItemIconCounts.getValue(value) - 1
                            itemTexturePathUses.getOrPut(texture, ::ArrayList).add(
                                TexturePathUse(
                                    jsonPath = name,
                                    value = value,
                                    rawLiteral = text.contains("\"$value\""),
                                    directItemIcon = directItemIcon,
                                )
                            )
                        }
                    }

                    if (name.startsWith(ENTITY_GEOMETRY_PATH) && name.endsWith(".geo.json", ignoreCase = true)) {
                        val ids = GEOMETRY_IDENTIFIER.findAll(text).map { it.groupValues[1] }.toSet()
                        if (ids.isNotEmpty()) {
                            geometryIdsByPath[name] = ids
                            val info = geometryInfo(name, text, allowRoundedUvOverflow = maxTextureDimension > 0)
                            if (info != null) geometryInfoById.getOrPut(info.identifier, ::ArrayList).add(info)
                            val remaining = strings.toMutableList()
                            ids.forEach { remaining.remove(it) }
                            references.addAll(remaining.filter(::isAssetReference))
                        } else {
                            references.addAll(strings.filter(::isAssetReference))
                        }
                    } else {
                        references.addAll(strings.filter(::isAssetReference))
                        if (name.startsWith("attachables/") || name.startsWith("entity/")) {
                            val geometryMap = simpleStringMap(text, "geometry")
                            val textureMap = simpleStringMap(text, "textures")
                            val materialMap = simpleStringMap(text, "materials")
                            if (maxTextureDimension > 0 && (geometryMap == null || textureMap == null)) {
                                hasUnresolvedAssetReferences = true
                            }
                            if (geometryMap != null && textureMap != null) {
                                textureMap.values.forEach { value ->
                                    val texture = textureEntryName(value) ?: return@forEach
                                    textureMapPathUses.getOrPut(texture, ::ArrayList).add(value)
                                }
                                val renderControllers = RENDER_CONTROLLER_REFERENCE.findAll(text)
                                    .map { it.groupValues[1] }.toSet()
                                var ambiguousAnimatedTextures = false
                                for (controller in renderControllers) {
                                    if (!uvAnimationScan.textureAliasesByController.containsKey(controller)) continue
                                    val aliases = uvAnimationScan.textureAliasesByController[controller]
                                    if (aliases == null) {
                                        ambiguousAnimatedTextures = true
                                        continue
                                    }
                                    for (alias in aliases) {
                                        val texture = textureEntryName(textureMap[alias] ?: "")
                                        if (texture == null) ambiguousAnimatedTextures = true
                                        else animatedTextureReferences += texture
                                    }
                                }
                                if (ambiguousAnimatedTextures) {
                                    textureMap.values.mapNotNull(::textureEntryName).forEach(animatedTextureReferences::add)
                                }
                                val geometryTextureLinks = linkedGeometryTextures(
                                    geometryMap, textureMap, materialMap, renderControllers,
                                    uvAnimationScan.geometryTextureAliasesByController,
                                )
                                if (geometryTextureLinks == null) {
                                    textureMap.values.mapNotNull(::textureEntryName)
                                        .forEach(unsafeGeometryTextures::add)
                                } else {
                                    for ((identifier, value) in geometryTextureLinks) {
                                        val texture = textureEntryName(value) ?: continue
                                        geometriesByTexture.getOrPut(texture, ::HashSet).add(identifier)
                                        texturesByGeometry.getOrPut(identifier, ::HashSet).add(texture)
                                        directTextureReferences[texture] = (directTextureReferences[texture] ?: 0) + 1
                                        directTexturePathUses.getOrPut(texture, ::ArrayList).add(value)
                                    }
                                }
                            }
                        }
                    }
                }

                val directDedup = if (allowDirectTextureDedup && !hasUnresolvedAssetReferences) {
                    deduplicateDirectTexturePaths(
                        zip,
                        textureEntrySizes,
                        directTextureReferences,
                        directTexturePathUses,
                        textureReferenceTokens,
                        animatedTextures,
                        animatedTextureReferences,
                        texturesByGeometry,
                        geometriesByTexture,
                    )
                } else TextureDedupPlan()

                val unreachable = if (hasUnresolvedAssetReferences) {
                    HashSet<String>()
                } else {
                    geometryIdsByPath
                        .filterValues { ids -> ids.none { it in references } }
                        .keys
                        .toMutableSet()
                }
                if (!hasUnresolvedAssetReferences) {
                    for (texture in stitchedTextures) {
                        val stem = texture.removeSuffix(".png")
                        val fileName = stem.substringAfterLast('/')
                        if (references.none { it.contains(stem) || it.contains(fileName) }) {
                            unreachable += texture
                            val animationMetadata = "$texture.mcmeta"
                            if (zip.getEntry(animationMetadata) != null) unreachable += animationMetadata
                        }
                    }
                }

                val textureCrops = HashMap<String, TextureCrop>()
                val geometryTransforms = HashMap<String, GeometryTransform>()
                val expectedTextureDimensions = HashMap<String, Pair<Int, Int>>()
                if (!hasUnclassifiedUvAnimation) {
                    for (identifiers in geometryComponents(texturesByGeometry.keys, geometriesByTexture)) {
                        val models = identifiers.mapNotNull { geometryInfoById[it]?.singleOrNull() }
                        if (models.size != identifiers.size || models.any { geometryIdsByPath[it.path]?.size != 1 } ||
                            models.map { it.path }.distinct().size != models.size ||
                            (maxTextureDimension == 0 && models.map { it.width to it.height }.distinct().size != 1)
                        ) continue
                        val textures = identifiers.flatMap { texturesByGeometry[it].orEmpty() }.toSet()
                        if (textures.isEmpty() || textures.any { geometriesByTexture[it].orEmpty().any { id -> id !in identifiers } }) {
                            continue
                        }

                        val width = models.first().width
                        val height = models.first().height
                        val firstTexture = textures.minOrNull() ?: continue
                        val firstEntry = zip.getEntry(firstTexture) ?: continue
                        val sourceDimensions = zip.getInputStream(firstEntry).use(PngOptimizer::dimensions) ?: continue
                        val sourceWidth = sourceDimensions.first
                        val sourceHeight = sourceDimensions.second
                        val uvCrop = planUvCrop(models, sourceWidth, sourceHeight) ?: continue
                        val crop = uvCrop.crop

                        val plannedTextures = ArrayList<Pair<String, TextureCrop>>(textures.size)
                        var supported = true
                        for (texture in textures) {
                            if (texture in animatedTextures || texture in animatedTextureReferences ||
                                directTextureReferences[texture] == null ||
                                !isDirectTextureOnly(texture, directTextureReferences.getValue(texture), textureReferenceTokens)
                            ) {
                                supported = false
                                break
                            }
                            val size = textureEntrySizes[texture] ?: -1L
                            if (size < 0L || size > MAX_PNG_BYTES) {
                                supported = false
                                break
                            }
                            val entry = zip.getEntry(texture)
                            if (entry == null ||
                                zip.getInputStream(entry).use(PngOptimizer::dimensions) != sourceDimensions
                            ) {
                                supported = false
                                break
                            }
                            val png = zip.getInputStream(entry)
                                .use { readBounded(it, MAX_PNG_BYTES) } ?: continue
                            if (PngOptimizer.planCropOrFull(png, crop.x, crop.y, crop.width, crop.height) != crop) {
                                supported = false
                                break
                            }
                            plannedTextures += texture to crop
                        }
                        if (!supported || plannedTextures.size != textures.size) continue

                        val resized = resizedDimensions(crop.width, crop.height, maxTextureDimension)
                        val shrunk = crop.x > 0 || crop.y > 0 ||
                            crop.width < sourceWidth || crop.height < sourceHeight ||
                            resized.first < crop.width || resized.second < crop.height
                        if (!shrunk) continue

                        plannedTextures.forEach { (texture, crop) ->
                            if (crop.x > 0 || crop.y > 0 ||
                                crop.width < crop.sourceWidth || crop.height < crop.sourceHeight
                            ) {
                                textureCrops[texture] = crop
                            }
                            expectedTextureDimensions[texture] = resized
                        }
                        models.forEach { geometryTransforms[it.path] = geometryTransform(it, crop, resized) }
                    }
                }

                val normalizedTextureMapPathUses = HashMap<String, MutableList<String>>()
                for ((_, values) in textureMapPathUses) {
                    for (value in values) {
                        val normalizedValue = directDedup.jsonStringRewrites[value] ?: value
                        val texture = textureEntryName(normalizedValue) ?: continue
                        normalizedTextureMapPathUses.getOrPut(texture, ::ArrayList).add(normalizedValue)
                    }
                }
                val croppedDedup = if (allowDirectTextureDedup && maxTextureDimension == 0 &&
                    !hasUnresolvedAssetReferences
                ) {
                    deduplicateTextureMapImages(
                        zip,
                        textureCrops,
                        textureEntrySizes,
                        normalizedTextureMapPathUses,
                        textureReferenceTokens,
                        animatedTextures,
                    )
                } else TextureDedupPlan()

                val itemDedup = if (hasUnresolvedAssetReferences) TextureDedupPlan()
                    else deduplicateItemTextures(zip, textureEntrySizes, itemTexturePathUses)
                unreachable += directDedup.removed
                unreachable += croppedDedup.removed
                unreachable += itemDedup.removed
                return OptimizationPlan(
                    unreachableEntries = unreachable,
                    textureCrops = textureCrops,
                    geometryTransforms = geometryTransforms,
                    expectedTextureDimensions = expectedTextureDimensions,
                    jsonStringRewrites = directDedup.jsonStringRewrites +
                        croppedDedup.jsonStringRewrites + itemDedup.jsonStringRewrites,
                    unsafeGeometryTextures = unsafeGeometryTextures,
                    disableTextureResizing = maxTextureDimension > 0 && hasUnresolvedAssetReferences,
                )
            }
        } catch (_: IOException) {
            return OptimizationPlan(disableTextureResizing = maxTextureDimension > 0)
        }
    }

    private fun findPackSetCropPlans(
        inputs: List<Path>,
        maxTextureDimension: Int,
        cropToUvExtent: Boolean,
    ): List<OptimizationPlan> {
        fun emptyPlans() = List(inputs.size) {
            OptimizationPlan(
                disableTextureResizing = maxTextureDimension > 0,
                disableGeometryTransforms = true,
                disableLocalPruning = true,
            )
        }
        if (inputs.size !in 1..MAX_PACK_SET_FILES) return emptyPlans()

        val zips = ArrayList<ZipFile>(inputs.size)
        try {
            for (input in inputs) {
                if (!Files.isRegularFile(input)) return emptyPlans()
                zips += ZipFile(input.toFile())
            }

            val geometryIdsByPath = HashMap<PackEntryKey, Set<String>>()
            val geometryInfoById = HashMap<String, MutableList<Pair<PackEntryKey, GeometryInfo>>>()
            val geometryContentHashes = HashMap<PackEntryKey, String>()
            val assetReferences = HashSet<String>()
            val assetReferenceCountsByPack = List(inputs.size) { HashMap<String, Int>() }
            val geometryReferenceCountsByPack = List(inputs.size) { HashMap<String, Int>() }
            val textureEntrySizes = HashMap<PackEntryKey, Long>()
            val textureKeysByPath = HashMap<String, MutableList<PackEntryKey>>()
            val entryNamesByPack = List(inputs.size) { HashSet<String>() }
            val textureReferences = HashMap<PackEntryKey, MutableList<PackSetTextureReference>>()
            val protectedEntries = List(inputs.size) { HashSet<String>() }
            val directTextureReferences = HashMap<PackEntryKey, Int>()
            val directTextureUses = HashMap<PackEntryKey, MutableList<PackSetTextureUse>>()
            val texturesByGeometry = HashMap<String, MutableSet<PackEntryKey>>()
            val geometriesByTexture = HashMap<PackEntryKey, MutableSet<String>>()
            val unclassifiedRenderControllerTextures = HashSet<PackEntryKey>()
            val animatedTextures = HashSet<PackEntryKey>()
            val animatedTextureReferences = HashSet<PackEntryKey>()
            val ambiguousTexturePaths = HashSet<String>()
            val attachments = ArrayList<PackSetAttachment>()
            val textureTokensByPack = List(inputs.size) { ArrayList<PackSetTextureReference>() }
            val animatedAliasesByController = HashMap<String, Set<String>?>()
            val geometryTextureAliasesByController = HashMap<String, List<GeometryTextureAliases>?>()
            var unknownUvAnimation = false
            var hasUnresolvedMaterialReferences = false
            var scannedBytes = 0L
            val flipbookTextureValuesByPack = List(inputs.size) { ArrayList<String>() }

            for (packIndex in zips.indices) {
                val zip = zips[packIndex]
                val uvScan = findUvAnimationControllers(zip)
                if (!uvScan.complete) unknownUvAnimation = true
                for ((controller, aliases) in uvScan.textureAliasesByController) {
                    if (!animatedAliasesByController.containsKey(controller)) {
                        animatedAliasesByController[controller] = aliases
                    } else if (animatedAliasesByController[controller] != aliases) {
                        animatedAliasesByController[controller] = null
                    }
                }
                mergeControllerAliases(
                    geometryTextureAliasesByController,
                    uvScan.geometryTextureAliasesByController,
                )

                val entries = zip.entries()
                var entriesScanned = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (++entriesScanned > MAX_ZIP_ENTRIES) return emptyPlans()
                    val name = entry.name
                    entryNamesByPack[packIndex] += name.lowercase(Locale.ROOT)
                    if (name.endsWith(".png", ignoreCase = true)) {
                        val key = PackEntryKey(packIndex, name)
                        textureEntrySizes[key] = entry.size
                        textureKeysByPath.getOrPut(name, ::ArrayList).add(key)
                    } else if (name.endsWith(".png.mcmeta", ignoreCase = true)) {
                        animatedTextures += PackEntryKey(packIndex, name.dropLast(".mcmeta".length))
                    }

                    if (name.endsWith(".material", ignoreCase = true)) {
                        val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) }
                        val text = bytes?.let(::decodeUtf8)
                        if (text == null || text.contains("textures/") || text.contains("geometry.")) {
                            hasUnresolvedMaterialReferences = true
                        }
                    }
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    if (entry.size > MAX_JSON_BYTES) return emptyPlans()
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) } ?: return emptyPlans()
                    scannedBytes += bytes.size
                    if (scannedBytes > MAX_PACK_SET_SCAN_BYTES) return emptyPlans()
                    val text = decodeUtf8(bytes) ?: return emptyPlans()
                    val strings = JsonStringScanner.read(text) ?: return emptyPlans()
                    if (text.contains("\"uv_anim\"") && !name.startsWith(RENDER_CONTROLLERS_PATH, ignoreCase = true)) {
                        unknownUvAnimation = true
                    }
                    strings.filter { it.contains("textures/") }.forEach { value ->
                        textureTokensByPack[packIndex] += PackSetTextureReference(packIndex, name, value)
                    }
                    if (name.equals(FLIPBOOK_TEXTURES_PATH, ignoreCase = true)) {
                        strings.filter { it.contains("textures/") }.forEach(flipbookTextureValuesByPack[packIndex]::add)
                    }

                    if (name.startsWith(ENTITY_GEOMETRY_PATH) && name.endsWith(".geo.json", ignoreCase = true)) {
                        val identifierMatches = GEOMETRY_IDENTIFIER.findAll(text).toList()
                        val ids = identifierMatches.map { it.groupValues[1] }.toSet()
                        if (ids.isNotEmpty()) {
                            val key = PackEntryKey(packIndex, name)
                            geometryIdsByPath[key] = ids
                            val info = geometryInfo(name, text, allowRoundedUvOverflow = maxTextureDimension > 0)
                            if (info != null) {
                                geometryInfoById.getOrPut(info.identifier, ::ArrayList).add(key to info)
                                if (identifierMatches.size == 1) {
                                    val normalized = text.replaceRange(
                                        identifierMatches.single().range,
                                        "\"identifier\":\"__FETTLE_GEOMETRY_ID__\"",
                                    )
                                    geometryContentHashes[key] = HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
                                    )
                                }
                            }
                            val references = strings.filter { it !in ids && isAssetReference(it) }
                            assetReferences += references
                            references.forEach { reference ->
                                val counts = assetReferenceCountsByPack[packIndex]
                                counts[reference] = (counts[reference] ?: 0) + 1
                            }
                        } else {
                            val references = strings.filter(::isAssetReference)
                            assetReferences += references
                            references.forEach { reference ->
                                val counts = assetReferenceCountsByPack[packIndex]
                                counts[reference] = (counts[reference] ?: 0) + 1
                            }
                        }
                    } else if (name.startsWith("attachables/") || name.startsWith("entity/")) {
                        val references = strings.filter(::isAssetReference)
                        assetReferences += references
                        references.forEach { reference ->
                            val counts = assetReferenceCountsByPack[packIndex]
                            counts[reference] = (counts[reference] ?: 0) + 1
                        }
                        val geometryMap = simpleStringMap(text, "geometry")
                        val textureMap = simpleStringMap(text, "textures")
                        geometryMap?.values?.filter { it.startsWith("geometry.") }?.forEach { identifier ->
                            val counts = geometryReferenceCountsByPack[packIndex]
                            counts[identifier] = (counts[identifier] ?: 0) + 1
                        }
                        if (geometryMap != null && textureMap != null) {
                            attachments += PackSetAttachment(
                                packIndex,
                                name,
                                geometryMap,
                                textureMap,
                                simpleStringMap(text, "materials"),
                                RENDER_CONTROLLER_REFERENCE.findAll(text).map { it.groupValues[1] }.toSet(),
                            )
                        }
                    } else {
                        val references = strings.filter(::isAssetReference)
                        assetReferences += references
                        references.forEach { reference ->
                            val counts = assetReferenceCountsByPack[packIndex]
                            counts[reference] = (counts[reference] ?: 0) + 1
                        }
                    }
                }
            }

            if (unknownUvAnimation || hasUnresolvedMaterialReferences) return emptyPlans()

            for (packIndex in flipbookTextureValuesByPack.indices) {
                for (value in flipbookTextureValuesByPack[packIndex]) {
                    val texture = textureEntryName(value) ?: continue
                    val key = resolvePackSetTexture(packIndex, texture, textureEntrySizes, textureKeysByPath)
                    if (key == null) {
                        if (textureKeysByPath[texture].orEmpty().size > 1) ambiguousTexturePaths += texture
                    } else {
                        animatedTextures += key
                    }
                }
            }

            for (packIndex in textureTokensByPack.indices) {
                for (reference in textureTokensByPack[packIndex]) {
                    val texture = textureEntryName(reference.value) ?: continue
                    val key = resolvePackSetTexture(packIndex, texture, textureEntrySizes, textureKeysByPath)
                    if (key == null) {
                        if (textureKeysByPath[texture].orEmpty().size > 1) ambiguousTexturePaths += texture
                    } else {
                        textureReferences.getOrPut(key, ::ArrayList).add(reference)
                        protectedEntries[key.packIndex] += key.path
                        if (key in animatedTextures) protectedEntries[key.packIndex] += "${key.path}.mcmeta"
                    }
                }
            }

            for ((path, identifiers) in geometryIdsByPath) {
                if (identifiers.any { it in assetReferences }) protectedEntries[path.packIndex] += path.path
            }

            for (attachment in attachments) {
                val geometryTextureLinks = linkedGeometryTextures(
                    attachment.geometry, attachment.textures, attachment.materials,
                    attachment.renderControllers, geometryTextureAliasesByController,
                )
                if (geometryTextureLinks == null) {
                    for (value in attachment.textures.values) {
                        val texture = textureEntryName(value) ?: continue
                        val localKey = PackEntryKey(attachment.packIndex, texture)
                        val candidates = if (localKey in textureEntrySizes) listOf(localKey)
                            else textureKeysByPath[texture].orEmpty()
                        unclassifiedRenderControllerTextures += candidates
                    }
                } else {
                    for ((identifier, value) in geometryTextureLinks) {
                        val texture = textureEntryName(value) ?: continue
                        val key = resolvePackSetTexture(
                            attachment.packIndex, texture, textureEntrySizes, textureKeysByPath
                        )
                        if (key == null) {
                            if (textureKeysByPath[texture].orEmpty().size > 1) ambiguousTexturePaths += texture
                            continue
                        }
                        texturesByGeometry.getOrPut(identifier, ::HashSet).add(key)
                        geometriesByTexture.getOrPut(key, ::HashSet).add(identifier)
                        directTextureReferences[key] = (directTextureReferences[key] ?: 0) + 1
                        directTextureUses.getOrPut(key, ::ArrayList).add(PackSetTextureUse(attachment.packIndex, value))
                    }
                }

                var ambiguousAnimation = false
                for (controller in attachment.renderControllers) {
                    if (!animatedAliasesByController.containsKey(controller)) continue
                    val aliases = animatedAliasesByController[controller]
                    if (aliases == null) {
                        ambiguousAnimation = true
                        continue
                    }
                    for (alias in aliases) {
                        val value = attachment.textures[alias]
                        if (value == null) {
                            ambiguousAnimation = true
                            continue
                        }
                        val texture = textureEntryName(value) ?: continue
                        resolvePackSetTexture(attachment.packIndex, texture, textureEntrySizes, textureKeysByPath)
                            ?.let(animatedTextureReferences::add)
                    }
                }
                if (ambiguousAnimation) {
                    attachment.textures.values.mapNotNull(::textureEntryName).forEach { texture ->
                        resolvePackSetTexture(attachment.packIndex, texture, textureEntrySizes, textureKeysByPath)
                            ?.let(animatedTextureReferences::add)
                    }
                }
            }

            val deduplicated = deduplicatePackSetTextures(
                zips,
                textureEntrySizes,
                textureKeysByPath,
                textureReferences,
                directTextureReferences,
                directTextureUses,
                ambiguousTexturePaths,
                animatedTextures,
                animatedTextureReferences,
                texturesByGeometry,
                geometriesByTexture,
                protectedEntries,
            )

            val attachmentTextureMapReferences = HashMap<PackEntryKey, MutableList<PackSetTextureReference>>()
            for (attachment in attachments) {
                for (value in attachment.textures.values) {
                    val normalizedValue = deduplicated.jsonStringRewrites[attachment.packIndex][value] ?: value
                    val path = textureEntryName(normalizedValue) ?: continue
                    val key = resolvePackSetTexture(
                        attachment.packIndex, path, textureEntrySizes, textureKeysByPath
                    ) ?: continue
                    attachmentTextureMapReferences.getOrPut(key, ::ArrayList).add(
                        PackSetTextureReference(attachment.packIndex, attachment.entryPath, normalizedValue)
                    )
                }
            }

            val textureCrops = List(inputs.size) { HashMap<String, TextureCrop>() }
            val geometryTransforms = List(inputs.size) { HashMap<String, GeometryTransform>() }
            val expectedTextureDimensions = List(inputs.size) { HashMap<String, Pair<Int, Int>>() }
            val unsafeGeometryTextures = List(inputs.size) { HashSet<String>() }
            val unsafeGeometryFiles = List(inputs.size) { HashSet<String>() }
            unclassifiedRenderControllerTextures.forEach { unsafeGeometryTextures[it.packIndex] += it.path }
            texturesByGeometry.values.flatten().forEach { unsafeGeometryTextures[it.packIndex] += it.path }
            geometryIdsByPath.keys.forEach { unsafeGeometryFiles[it.packIndex] += it.path }
            val textureMapUsesByTexture = HashMap<PackEntryKey, MutableList<PackSetTextureUse>>()
            for (attachment in attachments) {
                for (value in attachment.textures.values) {
                    val normalizedValue = deduplicated.jsonStringRewrites[attachment.packIndex][value] ?: value
                    val texture = textureEntryName(normalizedValue) ?: continue
                    val key = resolvePackSetTexture(
                        attachment.packIndex, texture, textureEntrySizes, textureKeysByPath
                    ) ?: continue
                    textureMapUsesByTexture.getOrPut(key, ::ArrayList)
                        .add(PackSetTextureUse(attachment.packIndex, normalizedValue))
                }
            }
            val atlasCandidates = ArrayList<AtlasComponent>()
            for (identifiers in geometryComponents(texturesByGeometry.keys, geometriesByTexture)) {
                if (unknownUvAnimation || hasUnresolvedMaterialReferences) continue
                val models = identifiers.mapNotNull { geometryInfoById[it]?.singleOrNull() }
                if (models.size != identifiers.size || models.any { geometryIdsByPath[it.first]?.size != 1 } ||
                    models.map { it.first }.distinct().size != models.size
                ) continue
                val textures = identifiers.flatMap { texturesByGeometry[it].orEmpty() }.toSet()
                if (textures.isEmpty() || textures.any { geometriesByTexture[it].orEmpty().any { id -> id !in identifiers } }) {
                    continue
                }
                val firstTexture = textures.first()
                val firstEntry = zips[firstTexture.packIndex].getEntry(firstTexture.path) ?: continue
                val sourceDimensions = zips[firstTexture.packIndex].getInputStream(firstEntry)
                    .use(PngOptimizer::dimensions) ?: continue
                val sourceWidth = sourceDimensions.first
                val sourceHeight = sourceDimensions.second
                val crop = if (cropToUvExtent) {
                    planUvCrop(models.map { it.second }, sourceWidth, sourceHeight)?.crop ?: continue
                } else {
                    TextureCrop(0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight)
                }
                val resized = resizedDimensions(crop.width, crop.height, maxTextureDimension)
                val requiresResize = resized.first < crop.width || resized.second < crop.height

                val plannedTextures = ArrayList<Pair<PackEntryKey, TextureCrop>>(textures.size)
                var supported = true
                for (textureKey in textures) {
                    val references = textureReferences[textureKey].orEmpty()
                    val referencesAreSafe = isDirectTextureOnly(
                        textureKey.path,
                        directTextureReferences[textureKey] ?: 0,
                        references.map { it.value },
                    ) || isAttachmentTextureMapOnly(
                        references,
                        attachmentTextureMapReferences[textureKey].orEmpty(),
                    )
                    if (textureKey.path in ambiguousTexturePaths || textureKey in animatedTextures ||
                        textureKey in animatedTextureReferences ||
                        directTextureReferences[textureKey] == null ||
                        textureKey in unclassifiedRenderControllerTextures ||
                        !referencesAreSafe
                    ) {
                        supported = false
                        break
                    }
                    val size = textureEntrySizes[textureKey] ?: -1L
                    if (size < 0L || size > MAX_PNG_BYTES) {
                        supported = false
                        break
                    }
                    val entry = zips[textureKey.packIndex].getEntry(textureKey.path)
                    val plannedCrop = entry?.let {
                        zips[textureKey.packIndex].getInputStream(it).use { stream ->
                            if (cropToUvExtent) {
                                val png = readBounded(stream, MAX_PNG_BYTES)
                                png?.let { bytes ->
                                    PngOptimizer.planCropOrFull(bytes, crop.x, crop.y, crop.width, crop.height)
                                }
                            } else {
                                val png = readBounded(stream, MAX_PNG_BYTES)
                                png?.takeIf {
                                    PngOptimizer.dimensions(it) == sourceDimensions &&
                                        (!requiresResize || PngOptimizer.supportsResize(it))
                                }?.let { crop }
                            }
                        }
                    }
                    if (plannedCrop != crop) {
                        supported = false
                        break
                    }
                    plannedTextures += textureKey to plannedCrop
                }
                if (!supported || plannedTextures.size != textures.size) continue

                if (maxTextureDimension == 0 && cropToUvExtent) {
                    val keys = plannedTextures.map { it.first }.sortedWith(compareBy({ it.packIndex }, { it.path }))
                    val hashes = keys.mapNotNull { key ->
                        val entry = zips[key.packIndex].getEntry(key.path) ?: return@mapNotNull null
                        val source = zips[key.packIndex].getInputStream(entry)
                            .use { readBounded(it, MAX_PNG_BYTES) } ?: return@mapNotNull null
                        PngOptimizer.cropRegion(source, crop)?.let(::textureFingerprint)
                    }
                    if (hashes.size == keys.size) {
                        val variantHashes = hashes.distinct().sorted()
                        val variantPages = variantHashes.map { hash ->
                            keys.filterIndexed { index, _ -> hashes[index] == hash }
                        }
                        atlasCandidates += AtlasComponent(models, keys, variantPages, variantHashes, crop)
                    }
                }

                val shrunk = crop.x > 0 || crop.y > 0 ||
                    crop.width < sourceWidth || crop.height < sourceHeight ||
                    resized.first < crop.width || resized.second < crop.height
                if (!shrunk) continue

                plannedTextures.forEach { (textureKey, crop) ->
                    if (crop.x > 0 || crop.y > 0 ||
                        crop.width < crop.sourceWidth || crop.height < crop.sourceHeight
                    ) {
                        textureCrops[textureKey.packIndex][textureKey.path] = crop
                    }
                    expectedTextureDimensions[textureKey.packIndex][textureKey.path] = resized
                    unsafeGeometryTextures[textureKey.packIndex].remove(textureKey.path)
                }
                models.forEach { (geometryKey, model) ->
                    geometryTransforms[geometryKey.packIndex][geometryKey.path] =
                        geometryTransform(model, crop, resized)
                    unsafeGeometryFiles[geometryKey.packIndex].remove(geometryKey.path)
                }
            }
            val atlasRemovedEntries = List(inputs.size) { HashSet<String>() }
            val atlasRewrites = List(inputs.size) { HashMap<String, String>() }
            val generatedEntries = List(inputs.size) { HashMap<String, ByteArray>() }
            val atlasedTextures = if (maxTextureDimension == 0 && cropToUvExtent) {
                buildLosslessAtlases(
                    atlasCandidates,
                    zips,
                    entryNamesByPack,
                    textureEntrySizes,
                    textureKeysByPath,
                    textureReferences,
                    deduplicated.jsonStringRewrites,
                    geometryTransforms,
                    atlasRemovedEntries,
                    atlasRewrites,
                    generatedEntries,
                    protectedEntries,
                    unsafeGeometryFiles,
                )
            } else emptySet()
            val croppedDedup = if (cropToUvExtent && maxTextureDimension == 0) {
                deduplicatePackSetTextureMapImages(
                    zips,
                    textureCrops,
                    textureEntrySizes,
                    textureKeysByPath,
                    textureReferences,
                    textureMapUsesByTexture,
                    ambiguousTexturePaths,
                    animatedTextures,
                    protectedEntries,
                    atlasedTextures,
                )
            } else PackSetTextureDedupPlan(
                List(inputs.size) { emptySet() }, List(inputs.size) { emptyMap() }
            )
            val geometryDedup = if (inputs.size > 1) {
                deduplicatePackSetGeometryDefinitions(
                    geometryIdsByPath,
                    geometryInfoById,
                    geometryContentHashes,
                    assetReferenceCountsByPack,
                    geometryReferenceCountsByPack,
                    geometryTransforms,
                    protectedEntries,
                )
            } else PackSetGeometryDedupPlan(
                List(inputs.size) { emptySet() }, List(inputs.size) { emptyMap() }
            )
            return inputs.indices.map { index ->
                OptimizationPlan(
                    unreachableEntries = deduplicated.removedEntries[index] +
                        croppedDedup.removedEntries[index] + geometryDedup.removedEntries[index] +
                        atlasRemovedEntries[index],
                    protectedEntries = protectedEntries[index],
                    textureCrops = textureCrops[index],
                    geometryTransforms = geometryTransforms[index],
                    expectedTextureDimensions = expectedTextureDimensions[index],
                    jsonStringRewrites = deduplicated.jsonStringRewrites[index] +
                        croppedDedup.jsonStringRewrites[index] + atlasRewrites[index] +
                        geometryDedup.jsonStringRewrites[index],
                    generatedEntries = generatedEntries[index],
                    unsafeGeometryTextures = unsafeGeometryTextures[index],
                    unsafeGeometryFiles = unsafeGeometryFiles[index],
                )
            }
        } catch (_: IOException) {
            return emptyPlans()
        } finally {
            zips.forEach { zip ->
                try {
                    zip.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun textureFingerprint(bytes: ByteArray): String =
        PngOptimizer.decodedPixelHash(bytes)?.let { "pixels:${HexFormat.of().formatHex(it)}" }
            ?: "bytes:${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}"

    private fun sameTextureContent(first: ByteArray, second: ByteArray): Boolean =
        first.contentEquals(second) || PngOptimizer.sameDecodedPixels(first, second)

    private fun buildLosslessAtlases(
        candidates: List<AtlasComponent>,
        zips: List<ZipFile>,
        entryNamesByPack: List<MutableSet<String>>,
        sizes: Map<PackEntryKey, Long>,
        entriesByPath: Map<String, List<PackEntryKey>>,
        references: Map<PackEntryKey, List<PackSetTextureReference>>,
        priorRewrites: List<Map<String, String>>,
        geometryTransforms: List<MutableMap<String, GeometryTransform>>,
        removedEntries: List<MutableSet<String>>,
        jsonStringRewrites: List<MutableMap<String, String>>,
        generatedEntries: List<MutableMap<String, ByteArray>>,
        protectedEntries: List<MutableSet<String>>,
        unsafeGeometryFiles: List<MutableSet<String>>,
    ): Set<PackEntryKey> {
        val atlased = HashSet<PackEntryKey>()
        val atlasPaths = HashMap<PackEntryKey, String>()
        val binsByVariantCount = HashMap<Int, MutableList<AtlasBin>>()
        val slotsByVariantCount = candidates.groupBy { it.variantCount }.mapValues { (_, components) ->
            components.groupBy { it.signature }.values.map(::AtlasSlot)
        }
        for ((variantCount, slots) in slotsByVariantCount) {
            val bins = binsByVariantCount.getOrPut(variantCount, ::ArrayList)
            val ordered = slots.sortedWith(
                compareByDescending<AtlasSlot> { maxOf(it.width, it.height) }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenBy { it.signature }
            )
            for (slot in ordered) {
                if (slot.width > MAX_ATLAS_DIMENSION || slot.height > MAX_ATLAS_DIMENSION) continue
                if (bins.none { it.place(slot) }) {
                    val bin = AtlasBin()
                    if (bin.place(slot)) bins += bin
                }
            }
        }

        for ((variantCount, bins) in binsByVariantCount.toSortedMap()) {
            for (bin in bins) {
                val sourceTextures = bin.placements.flatMap { placement ->
                    placement.slot.components.flatMap { it.textures }
                }.distinct()
                if (sourceTextures.size <= variantCount) continue
                val sourcePixels = bin.placements.flatMap { placement ->
                    placement.slot.components.flatMap { component ->
                        component.textures.map { it to component.crop.sourceWidth.toLong() * component.crop.sourceHeight }
                    }
                }.distinctBy { it.first }.sumOf { it.second }
                val atlasPixels = bin.usedWidth.toLong() * bin.usedHeight * variantCount
                if (atlasPixels > sourcePixels) continue
                val signature = "$variantCount\n" + bin.placements
                    .map { it.slot.signature }.sorted().joinToString("\n")
                val hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(StandardCharsets.UTF_8))
                )
                val pagePaths = (16..64 step 4).mapNotNull { hashLength ->
                    val paths = (0 until variantCount).map { page ->
                        "textures/entity/fettle/atlases/${hash.take(hashLength)}-$page.png"
                    }
                    paths.takeUnless { candidate -> candidate.any { path ->
                        val normalized = path.lowercase(Locale.ROOT)
                        entryNamesByPack.any { normalized in it }
                    } }
                }.firstOrNull() ?: throw IOException("Could not find an unused generated texture atlas path")

                val outputPack = bin.placements.flatMap { placement ->
                    placement.slot.components.flatMap { component ->
                        component.models.map { it.first.packIndex } + component.textures.map { it.packIndex }
                    }
                }.minOrNull() ?: continue

                for (page in 0 until variantCount) {
                    val atlas = PngAtlasBuilder(bin.usedWidth, bin.usedHeight)
                    for (placement in bin.placements) {
                        val component = placement.slot.component
                        val texture = component.variantPages[page].first()
                        val entry = zips[texture.packIndex].getEntry(texture.path)
                            ?: throw IOException("Atlas source texture is missing: ${texture.path}")
                        val source = zips[texture.packIndex].getInputStream(entry)
                            .use { readBounded(it, MAX_PNG_BYTES) }
                            ?: throw IOException("Atlas source texture exceeded the bounded read size: ${texture.path}")
                        val cropped = PngOptimizer.cropRegion(source, component.crop)
                            ?: throw IOException("Texture could not be safely cropped for an atlas: ${texture.path}")
                        val contentX = placement.x + ATLAS_GUTTER
                        val contentY = placement.y + ATLAS_GUTTER
                        if (!atlas.add(cropped, contentX, contentY, ATLAS_GUTTER)) {
                            throw IOException("Texture could not be safely added to an atlas: ${texture.path}")
                        }
                    }
                    val bytes = atlas.encode() ?: throw IOException("Generated texture atlas failed its pixel check")
                    val pagePath = pagePaths[page]
                    generatedEntries[outputPack][pagePath] = bytes
                    entryNamesByPack[outputPack] += pagePath.lowercase(Locale.ROOT)
                    for (placement in bin.placements) {
                        for (component in placement.slot.components) {
                            for (texture in component.variantPages[page]) {
                                atlasPaths[texture] = pagePath
                                atlased += texture
                                removedEntries[texture.packIndex] += texture.path
                                protectedEntries[texture.packIndex].remove(texture.path)
                            }
                        }
                    }
                }

                for (placement in bin.placements) {
                    val contentX = placement.x + ATLAS_GUTTER
                    val contentY = placement.y + ATLAS_GUTTER
                    for (component in placement.slot.components) {
                        component.models.forEach { (geometry, model) ->
                            val transform = GeometryTransform(
                                width = bin.usedWidth,
                                height = bin.usedHeight,
                                uScale = component.crop.sourceWidth.toDouble() / model.width,
                                vScale = component.crop.sourceHeight.toDouble() / model.height,
                                uOffset = (component.crop.x - contentX).toDouble(),
                                vOffset = (component.crop.y - contentY).toDouble(),
                            )
                            geometryTransforms[geometry.packIndex][geometry.path] = transform
                            unsafeGeometryFiles[geometry.packIndex].remove(geometry.path)
                        }
                    }
                }
            }
        }

        for ((texture, atlasPath) in atlasPaths) {
            for (reference in references[texture].orEmpty()) {
                jsonStringRewrites[reference.consumerPackIndex][reference.value] =
                    rewriteTextureValue(reference.value, atlasPath)
            }
        }
        for (packIndex in priorRewrites.indices) {
            for ((oldValue, canonicalValue) in priorRewrites[packIndex]) {
                if (oldValue in jsonStringRewrites[packIndex]) continue
                val texture = textureEntryName(canonicalValue) ?: continue
                val key = resolvePackSetTexture(packIndex, texture, sizes, entriesByPath) ?: continue
                val atlasPath = atlasPaths[key] ?: continue
                jsonStringRewrites[packIndex][oldValue] = rewriteTextureValue(canonicalValue, atlasPath)
            }
        }
        return atlased
    }

    private fun deduplicatePackSetTextureMapImages(
        zips: List<ZipFile>,
        crops: List<Map<String, TextureCrop>>,
        sizes: Map<PackEntryKey, Long>,
        entriesByPath: Map<String, List<PackEntryKey>>,
        references: Map<PackEntryKey, List<PackSetTextureReference>>,
        textureMapUses: Map<PackEntryKey, List<PackSetTextureUse>>,
        ambiguousPaths: Set<String>,
        animatedTextures: Set<PackEntryKey>,
        protectedEntries: List<MutableSet<String>>,
        excluded: Set<PackEntryKey>,
    ): PackSetTextureDedupPlan {
        val pathsByHash = HashMap<Pair<Int, String>, MutableList<PackEntryKey>>()
        for ((texture, uses) in textureMapUses) {
            if (texture in excluded) continue
            val crop = crops.getOrNull(texture.packIndex)?.get(texture.path)
            val refs = references[texture].orEmpty()
            val size = sizes[texture] ?: continue
            if (texture.path.startsWith(JAVA_RENDERED_ITEM_PATH, ignoreCase = true) ||
                texture.path in ambiguousPaths || entriesByPath[texture.path].orEmpty().size != 1 ||
                texture in animatedTextures ||
                uses.isEmpty() ||
                refs.size != uses.size ||
                uses.map { it.value }.groupingBy { it }.eachCount() !=
                    refs.map { it.value }.groupingBy { it }.eachCount() ||
                uses.any { it.consumerPackIndex != texture.packIndex ||
                    it.value != texture.path && it.value != textureStem(texture.path) } ||
                refs.any { it.consumerPackIndex != texture.packIndex } ||
                !isDirectTextureOnly(texture.path, uses.size, uses.map { it.value })
            ) continue
            if (size < 0L || size > MAX_PNG_BYTES) continue
            val entry = zips[texture.packIndex].getEntry(texture.path) ?: continue
            val source = zips[texture.packIndex].getInputStream(entry).use { readBounded(it, MAX_PNG_BYTES) }
                ?: continue
            if (crop == null && PngOptimizer.dimensions(source) == null) continue
            val output = if (crop == null) source else PngOptimizer.cropRegion(source, crop) ?: continue
            val hash = textureFingerprint(output)
            pathsByHash.getOrPut(texture.packIndex to hash, ::ArrayList).add(texture)
        }

        val removed = List(zips.size) { HashSet<String>() }
        val rewrites = List(zips.size) { HashMap<String, String>() }
        for (paths in pathsByHash.values) {
            val distinct = paths.distinct().sortedBy { it.path }
            if (distinct.size < 2) continue
            val canonical = distinct.first()
            val canonicalEntry = zips[canonical.packIndex].getEntry(canonical.path) ?: continue
            val canonicalBytes = zips[canonical.packIndex].getInputStream(canonicalEntry)
                .use { readBounded(it, MAX_PNG_BYTES) } ?: continue
            val canonicalCrop = crops[canonical.packIndex][canonical.path]
            val canonicalOutput = if (canonicalCrop == null) canonicalBytes
                else PngOptimizer.cropRegion(canonicalBytes, canonicalCrop) ?: continue
            for (duplicate in distinct.drop(1)) {
                val duplicateEntry = zips[duplicate.packIndex].getEntry(duplicate.path) ?: continue
                val duplicateBytes = zips[duplicate.packIndex].getInputStream(duplicateEntry)
                    .use { readBounded(it, MAX_PNG_BYTES) } ?: continue
                val duplicateCrop = crops[duplicate.packIndex][duplicate.path]
                val duplicateOutput = if (duplicateCrop == null) duplicateBytes
                    else PngOptimizer.cropRegion(duplicateBytes, duplicateCrop) ?: continue
                if (!sameTextureContent(canonicalOutput, duplicateOutput)) continue
                val duplicateUses = references.getValue(duplicate)
                val replacements = HashMap<String, String>()
                for (use in duplicateUses) {
                    val rewrite = rewriteTextureValue(use.value, canonical.path)
                    val previous = replacements.putIfAbsent(use.value, rewrite)
                    if (previous != null && previous != rewrite) {
                        replacements.clear()
                        break
                    }
                }
                if (replacements.isEmpty()) continue
                removed[duplicate.packIndex].add(duplicate.path)
                rewrites[duplicate.packIndex].putAll(replacements)
                protectedEntries[duplicate.packIndex].remove(duplicate.path)
            }
        }
        return PackSetTextureDedupPlan(removed, rewrites)
    }

    private fun deduplicatePackSetGeometryDefinitions(
        geometryIdsByPath: Map<PackEntryKey, Set<String>>,
        geometryInfoById: Map<String, List<Pair<PackEntryKey, GeometryInfo>>>,
        contentHashes: Map<PackEntryKey, String>,
        assetReferenceCountsByPack: List<Map<String, Int>>,
        geometryReferenceCountsByPack: List<Map<String, Int>>,
        geometryTransforms: List<Map<String, GeometryTransform>>,
        protectedEntries: List<MutableSet<String>>,
    ): PackSetGeometryDedupPlan {
        val definitionCounts = geometryIdsByPath.values.flatMap { it }.groupingBy { it }.eachCount()
        val pathsByPackAndContent = HashMap<Pair<Int, String>, MutableList<Pair<PackEntryKey, String>>>()
        for ((key, identifiers) in geometryIdsByPath) {
            val identifier = identifiers.singleOrNull() ?: continue
            val model = geometryInfoById[identifier]?.singleOrNull() ?: continue
            if (model.first != key || definitionCounts[identifier] != 1 ||
                assetReferenceCountsByPack[key.packIndex][identifier] == null ||
                assetReferenceCountsByPack[key.packIndex][identifier] !=
                    geometryReferenceCountsByPack[key.packIndex][identifier] ||
                assetReferenceCountsByPack.indices.any { index ->
                    index != key.packIndex && (assetReferenceCountsByPack[index][identifier] ?: 0) > 0
                }
            ) continue
            val hash = contentHashes[key] ?: continue
            pathsByPackAndContent.getOrPut(key.packIndex to hash, ::ArrayList).add(key to identifier)
        }

        val removedEntries = List(assetReferenceCountsByPack.size) { HashSet<String>() }
        val jsonStringRewrites = List(assetReferenceCountsByPack.size) { HashMap<String, String>() }
        for (definitions in pathsByPackAndContent.values) {
            val distinct = definitions.distinctBy { it.first }.sortedBy { it.second }
            if (distinct.size < 2 || distinct.map { it.second }.distinct().size != distinct.size) continue
            if (distinct.map { (key, _) -> geometryTransforms[key.packIndex][key.path] }.distinct().size != 1) continue
            val canonical = distinct.first().second
            for ((key, identifier) in distinct.drop(1)) {
                removedEntries[key.packIndex].add(key.path)
                protectedEntries[key.packIndex].remove(key.path)
                jsonStringRewrites[key.packIndex][identifier] = canonical
            }
        }
        return PackSetGeometryDedupPlan(removedEntries, jsonStringRewrites)
    }

    private fun deduplicatePackSetTextures(
        zips: List<ZipFile>,
        sizes: Map<PackEntryKey, Long>,
        entriesByPath: Map<String, List<PackEntryKey>>,
        references: MutableMap<PackEntryKey, MutableList<PackSetTextureReference>>,
        directReferences: MutableMap<PackEntryKey, Int>,
        directUses: Map<PackEntryKey, List<PackSetTextureUse>>,
        ambiguousPaths: Set<String>,
        animatedTextures: Set<PackEntryKey>,
        animatedTextureReferences: Set<PackEntryKey>,
        texturesByGeometry: MutableMap<String, MutableSet<PackEntryKey>>,
        geometriesByTexture: MutableMap<PackEntryKey, MutableSet<String>>,
        protectedEntries: List<MutableSet<String>>,
    ): PackSetTextureDedupPlan {
        val candidatesByPackAndSize = HashMap<Pair<Int, Long>, MutableList<PackEntryKey>>()
        for ((texture, directCount) in directReferences) {
            val uses = directUses[texture].orEmpty()
            val textureReferences = references[texture].orEmpty()
            val size = sizes[texture] ?: continue
            if (texture.path.startsWith(JAVA_RENDERED_ITEM_PATH, ignoreCase = true) ||
                texture.path in ambiguousPaths || entriesByPath[texture.path].orEmpty().size != 1 ||
                texture in animatedTextures || texture in animatedTextureReferences ||
                size < 0L || size > MAX_PNG_BYTES ||
                directCount != uses.size || uses.size != textureReferences.size || uses.isEmpty() ||
                uses.any { it.consumerPackIndex != texture.packIndex ||
                    it.value != texture.path && it.value != textureStem(texture.path) } ||
                textureReferences.any { it.consumerPackIndex != texture.packIndex } ||
                uses.map { it.value }.groupingBy { it }.eachCount() !=
                    textureReferences.map { it.value }.groupingBy { it }.eachCount() ||
                !isDirectTextureOnly(texture.path, directCount, textureReferences.map { it.value })
            ) continue
            candidatesByPackAndSize.getOrPut(texture.packIndex to size, ::ArrayList).add(texture)
        }

        val pathsByHash = HashMap<Pair<Int, String>, MutableList<PackEntryKey>>()
        for ((packAndSize, candidates) in candidatesByPackAndSize) {
            if (candidates.size < 2) continue
            for (texture in candidates) {
                val entry = zips[texture.packIndex].getEntry(texture.path) ?: continue
                val bytes = zips[texture.packIndex].getInputStream(entry).use { readBounded(it, MAX_PNG_BYTES) }
                    ?: continue
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                pathsByHash.getOrPut(packAndSize.first to hash, ::ArrayList).add(texture)
            }
        }

        val pathRewrites = HashMap<PackEntryKey, PackEntryKey>()
        val removedEntries = List(zips.size) { HashSet<String>() }
        val jsonStringRewrites = List(zips.size) { HashMap<String, String>() }
        for ((_, candidates) in pathsByHash) {
            val paths = candidates.distinct().sortedBy { it.path }
            if (paths.size < 2) continue
            val canonical = paths.first()
            val groupRewrites = HashMap<String, String>()
            var conflict = false
            for (duplicate in paths.drop(1)) {
                for (use in directUses.getValue(duplicate)) {
                    val rewrite = rewriteTextureValue(use.value, canonical.path)
                    val previous = groupRewrites.putIfAbsent(use.value, rewrite)
                    if (previous != null && previous != rewrite) conflict = true
                }
            }
            if (conflict) continue
            for (duplicate in paths.drop(1)) {
                pathRewrites[duplicate] = canonical
                removedEntries[duplicate.packIndex].add(duplicate.path)
                directUses.getValue(duplicate).forEach { use ->
                    jsonStringRewrites[use.consumerPackIndex][use.value] = rewriteTextureValue(use.value, canonical.path)
                }
            }
        }

        if (pathRewrites.isNotEmpty()) {
            for ((identifier, textures) in texturesByGeometry.toMap()) {
                texturesByGeometry[identifier] = textures.map { pathRewrites[it] ?: it }.toMutableSet()
            }
            geometriesByTexture.clear()
            for ((identifier, textures) in texturesByGeometry) {
                textures.forEach { geometriesByTexture.getOrPut(it, ::HashSet).add(identifier) }
            }
            val normalizedReferences = HashMap<PackEntryKey, MutableList<PackSetTextureReference>>()
            for ((texture, values) in references) {
                val canonical = pathRewrites[texture] ?: texture
                for (value in values) {
                    val mapped = if (canonical == texture) value.value
                        else rewriteTextureValue(value.value, canonical.path)
                    normalizedReferences.getOrPut(canonical, ::ArrayList).add(
                        PackSetTextureReference(value.consumerPackIndex, value.consumerEntryPath, mapped)
                    )
                }
            }
            references.clear()
            references.putAll(normalizedReferences)
            val normalizedCounts = HashMap<PackEntryKey, Int>()
            for ((texture, count) in directReferences) {
                val canonical = pathRewrites[texture] ?: texture
                normalizedCounts[canonical] = (normalizedCounts[canonical] ?: 0) + count
            }
            directReferences.clear()
            directReferences.putAll(normalizedCounts)
            for ((duplicate, _) in pathRewrites) protectedEntries[duplicate.packIndex].remove(duplicate.path)
        }
        return PackSetTextureDedupPlan(removedEntries, jsonStringRewrites)
    }

    private fun resolvePackSetTexture(
        packIndex: Int,
        path: String,
        entries: Map<PackEntryKey, Long>,
        entriesByPath: Map<String, List<PackEntryKey>>,
    ): PackEntryKey? {
        val local = PackEntryKey(packIndex, path)
        if (local in entries) return local
        return entriesByPath[path]?.singleOrNull()
    }

    private fun findUvAnimationControllers(zip: ZipFile): UvAnimationScan {
        val textureAliasesByController = HashMap<String, Set<String>?>()
        val geometryTextureAliasesByController = HashMap<String, List<GeometryTextureAliases>?>()
        var complete = true
        val entries = zip.entries()
        var entriesScanned = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (++entriesScanned > MAX_ZIP_ENTRIES) {
                return UvAnimationScan(emptyMap(), emptyMap(), complete = false)
            }
            if (entry.isDirectory || !entry.name.startsWith(RENDER_CONTROLLERS_PATH, ignoreCase = true) ||
                !entry.name.endsWith(".json", ignoreCase = true)
            ) continue
            if (entry.size > MAX_JSON_BYTES) {
                complete = false
                continue
            }
            val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) }
            if (bytes == null) {
                complete = false
                continue
            }
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                complete = false
                continue
            }
            val hasUvAnimation = text.contains("\"uv_anim\"")
            var classified = false
            for (match in RENDER_CONTROLLER_DEFINITION.findAll(text)) {
                val openBrace = match.range.last
                val closeBrace = matchingJsonObjectEnd(text, openBrace)
                if (closeBrace == null) {
                    complete = false
                    continue
                }
                val body = text.substring(openBrace, closeBrace + 1)
                val staticAliases = staticGeometryTextureAliases(body)
                val controller = match.groupValues[1]
                if (!geometryTextureAliasesByController.containsKey(controller)) {
                    geometryTextureAliasesByController[controller] = staticAliases
                } else if (geometryTextureAliasesByController[controller] != staticAliases) {
                    geometryTextureAliasesByController[controller] = null
                }
                if (!body.contains("\"uv_anim\"")) continue
                classified = true
                val values = jsonStringArrayProperty(body, "textures")
                val aliases = values
                    ?.takeIf { it.isNotEmpty() && it.all { value -> value.startsWith("Texture.") && value.length > 8 } }
                    ?.map { it.removePrefix("Texture.") }
                    ?.toSet()
                textureAliasesByController[controller] = aliases
            }
            if (hasUvAnimation && !classified) complete = false
        }
        return UvAnimationScan(textureAliasesByController, geometryTextureAliasesByController, complete)
    }

    private fun matchingJsonObjectEnd(text: String, openBrace: Int): Int? {
        if (text.getOrNull(openBrace) != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openBrace until text.length) {
            val character = text[index]
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
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun jsonStringArrayProperty(text: String, property: String): List<String>? {
        val matches = Regex("\"${Regex.escape(property)}\"\\s*:\\s*\\[").findAll(text).toList()
        if (matches.size != 1) return null
        val openBracket = matches.single().range.last
        val closeBracket = matchingJsonArrayEnd(text, openBracket) ?: return null
        return JsonStringScanner.read(text.substring(openBracket, closeBracket + 1))
    }

    private fun matchingJsonArrayEnd(text: String, openBracket: Int): Int? {
        if (text.getOrNull(openBracket) != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openBracket until text.length) {
            val character = text[index]
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
                '[' -> depth++
                ']' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun findAnimatedTextures(input: Path): Set<String> {
        val textures = HashSet<String>()
        ZipFile(input.toFile()).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > MAX_ZIP_ENTRIES) throw IOException("Pack has more than $MAX_ZIP_ENTRIES ZIP entries")
                if (!entry.isDirectory && entry.name.endsWith(".png.mcmeta", ignoreCase = true)) {
                    textures += entry.name.dropLast(".mcmeta".length)
                } else if (!entry.isDirectory && entry.name.equals(FLIPBOOK_TEXTURES_PATH, ignoreCase = true)) {
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_JSON_BYTES) } ?: continue
                    val references = decodeUtf8(bytes)?.let(JsonStringScanner::read) ?: continue
                    references.filter { it.contains("textures/") }
                        .mapNotNull(::textureEntryName).forEach(textures::add)
                }
            }
        }
        return textures
    }

    private fun deduplicateDirectTexturePaths(
        zip: ZipFile,
        sizes: Map<String, Long>,
        directReferences: MutableMap<String, Int>,
        usesByTexture: Map<String, List<String>>,
        referenceTokens: MutableList<String>,
        animatedTextures: Set<String>,
        animatedTextureReferences: Set<String>,
        texturesByGeometry: MutableMap<String, MutableSet<String>>,
        geometriesByTexture: MutableMap<String, MutableSet<String>>,
    ): TextureDedupPlan {
        val candidatesBySize = HashMap<Long, MutableList<String>>()
        for ((texture, uses) in usesByTexture) {
            val directCount = directReferences[texture] ?: continue
            if (texture.startsWith(JAVA_RENDERED_ITEM_PATH) || texture in animatedTextures ||
                texture in animatedTextureReferences ||
                directCount != uses.size ||
                uses.any { it != texture && it != textureStem(texture) } ||
                !isDirectTextureOnly(texture, directCount, referenceTokens)
            ) continue
            val references = referenceTokens.filter { textureEntryName(it) == texture }
            if (references.size != uses.size || references.groupingBy { it }.eachCount() != uses.groupingBy { it }.eachCount()) {
                continue
            }
            val size = sizes[texture] ?: continue
            if (size < 0L || size > MAX_PNG_BYTES) continue
            candidatesBySize.getOrPut(size, ::ArrayList).add(texture)
        }

        val pathsByHash = HashMap<String, MutableList<String>>()
        for (paths in candidatesBySize.values) {
            if (paths.size < 2) continue
            for (texture in paths) {
                val bytes = zip.getInputStream(zip.getEntry(texture) ?: continue)
                    .use { readBounded(it, MAX_PNG_BYTES) } ?: continue
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                pathsByHash.getOrPut(hash, ::ArrayList).add(texture)
            }
        }

        val removed = HashSet<String>()
        val rewrites = HashMap<String, String>()
        val pathRewrites = HashMap<String, String>()
        for (paths in pathsByHash.values) {
            if (paths.size < 2) continue
            val canonical = paths.minOrNull() ?: continue
            for (duplicate in paths) {
                if (duplicate == canonical) continue
                removed += duplicate
                pathRewrites[duplicate] = canonical
                for (use in usesByTexture.getValue(duplicate)) {
                    rewrites[use] = rewriteTextureValue(use, canonical)
                }
            }
        }
        if (pathRewrites.isNotEmpty()) {
            for ((identifier, paths) in texturesByGeometry.toMap()) {
                texturesByGeometry[identifier] = paths.map { pathRewrites[it] ?: it }.toMutableSet()
            }
            geometriesByTexture.clear()
            for ((identifier, paths) in texturesByGeometry) {
                paths.forEach { geometriesByTexture.getOrPut(it, ::HashSet).add(identifier) }
            }
            val normalizedReferences = HashMap<String, Int>()
            for ((texture, count) in directReferences) {
                val canonical = pathRewrites[texture] ?: texture
                normalizedReferences[canonical] = (normalizedReferences[canonical] ?: 0) + count
            }
            directReferences.clear()
            directReferences.putAll(normalizedReferences)
            for (index in referenceTokens.indices) {
                val value = referenceTokens[index]
                val texture = textureEntryName(value) ?: continue
                val canonical = pathRewrites[texture] ?: continue
                referenceTokens[index] = rewriteTextureValue(value, canonical)
            }
        }
        return TextureDedupPlan(removed, rewrites)
    }

    private fun textureStem(path: String): String =
        if (path.endsWith(".png", ignoreCase = true)) path.dropLast(4) else path

    private fun rewriteTextureValue(value: String, canonicalPath: String): String =
        if (value.endsWith(".png", ignoreCase = true)) canonicalPath else textureStem(canonicalPath)

    private fun deduplicateTextureMapImages(
        zip: ZipFile,
        crops: Map<String, TextureCrop>,
        sizes: Map<String, Long>,
        textureMapUses: Map<String, List<String>>,
        referenceTokens: List<String>,
        animatedTextures: Set<String>,
    ): TextureDedupPlan {
        val pathsByHash = HashMap<String, MutableList<String>>()
        for ((texture, uses) in textureMapUses) {
            val crop = crops[texture]
            val references = referenceTokens.filter { textureEntryName(it) == texture }
            val size = sizes[texture] ?: continue
            if (texture.startsWith(JAVA_RENDERED_ITEM_PATH, ignoreCase = true) ||
                texture in animatedTextures ||
                uses.isEmpty() || uses.size != references.size ||
                uses.any { it != texture && it != textureStem(texture) } ||
                uses.groupingBy { it }.eachCount() != references.groupingBy { it }.eachCount() ||
                !isDirectTextureOnly(texture, references.size, references)
            ) continue
            if (size < 0L || size > MAX_PNG_BYTES) continue
            val entry = zip.getEntry(texture) ?: continue
            val source = zip.getInputStream(entry).use { readBounded(it, MAX_PNG_BYTES) } ?: continue
            if (crop == null && PngOptimizer.dimensions(source) == null) continue
            val output = if (crop == null) source else PngOptimizer.cropRegion(source, crop) ?: continue
            val hash = textureFingerprint(output)
            pathsByHash.getOrPut(hash, ::ArrayList).add(texture)
        }

        val removed = HashSet<String>()
        val rewrites = HashMap<String, String>()
        for (paths in pathsByHash.values) {
            val distinct = paths.distinct().sorted()
            if (distinct.size < 2) continue
            val canonical = distinct.first()
            val canonicalEntry = zip.getEntry(canonical) ?: continue
            val canonicalBytes = zip.getInputStream(canonicalEntry).use { readBounded(it, MAX_PNG_BYTES) } ?: continue
            val canonicalCrop = crops[canonical]
            val canonicalOutput = if (canonicalCrop == null) canonicalBytes
                else PngOptimizer.cropRegion(canonicalBytes, canonicalCrop) ?: continue
            for (duplicate in distinct.drop(1)) {
                val duplicateEntry = zip.getEntry(duplicate) ?: continue
                val duplicateBytes = zip.getInputStream(duplicateEntry).use { readBounded(it, MAX_PNG_BYTES) }
                    ?: continue
                val duplicateCrop = crops[duplicate]
                val duplicateOutput = if (duplicateCrop == null) duplicateBytes
                    else PngOptimizer.cropRegion(duplicateBytes, duplicateCrop) ?: continue
                if (!sameTextureContent(canonicalOutput, duplicateOutput)) continue
                val groupRewrites = textureMapUses.getValue(duplicate)
                    .associateWith { rewriteTextureValue(it, canonical) }
                if (groupRewrites.keys.isEmpty()) continue
                removed += duplicate
                rewrites.putAll(groupRewrites)
            }
        }
        return TextureDedupPlan(removed, rewrites)
    }

    private fun deduplicateItemTextures(
        zip: ZipFile,
        sizes: Map<String, Long>,
        usesByTexture: Map<String, List<TexturePathUse>>,
    ): TextureDedupPlan {
        val pathsByHash = HashMap<String, MutableList<String>>()
        for ((texture, uses) in usesByTexture) {
            if (!texture.startsWith(JAVA_RENDERED_ITEM_PATH) || uses.isEmpty()) continue
            val stem = texture.removeSuffix(".png")
            if (uses.any {
                    it.jsonPath != ITEM_TEXTURE_JSON_PATH || !it.rawLiteral || !it.directItemIcon ||
                        (it.value != stem && it.value != texture)
                }
            ) continue
            val size = sizes[texture] ?: continue
            if (size < 0L || size > MAX_PNG_BYTES) continue
            val bytes = zip.getInputStream(zip.getEntry(texture) ?: continue)
                .use { readBounded(it, MAX_PNG_BYTES) } ?: continue
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            pathsByHash.getOrPut(hash, ::ArrayList).add(texture)
        }

        val removed = HashSet<String>()
        val rewrites = HashMap<String, String>()
        for (paths in pathsByHash.values) {
            if (paths.size < 2) continue
            val canonical = paths.minOrNull() ?: continue
            for (duplicate in paths) {
                if (duplicate == canonical) continue
                removed += duplicate
                val canonicalStem = canonical.removeSuffix(".png")
                for (use in usesByTexture.getValue(duplicate)) {
                    rewrites[use.value] = if (use.value.endsWith(".png", ignoreCase = true)) canonical else canonicalStem
                }
            }
        }
        return TextureDedupPlan(removed, rewrites)
    }

    private fun geometryInfo(
        path: String,
        text: String,
        allowRoundedUvOverflow: Boolean,
    ): GeometryInfo? {
        val identifier = GEOMETRY_IDENTIFIER.findAll(text).map { it.groupValues[1] }.toList().singleOrNull() ?: return null
        val widthMatch = TEXTURE_WIDTH.findAll(text).toList().singleOrNull() ?: return null
        val heightMatch = TEXTURE_HEIGHT.findAll(text).toList().singleOrNull() ?: return null
        if (NORMALIZED_UVS_TRUE.containsMatchIn(text) ||
            text.contains("\"poly_mesh\"") || text.contains("\"texture_meshes\"") ||
            text.contains("\"material_instance\"")
        ) return null

        val uvCount = UV_ARRAY_COUNT.findAll(text).count()
        val uvSizeCount = UV_SIZE_COUNT.findAll(text).count()
        val faces = UV_FACE.findAll(text).toList()
        if (uvCount == 0 || uvCount != uvSizeCount || uvCount != faces.size) return null

        val width = widthMatch.groupValues[2].toIntOrNull() ?: return null
        val height = heightMatch.groupValues[2].toIntOrNull() ?: return null
        var minU = Double.POSITIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (face in faces) {
            val u = face.groupValues[1].toDoubleOrNull() ?: return null
            val v = face.groupValues[2].toDoubleOrNull() ?: return null
            val du = face.groupValues[3].toDoubleOrNull() ?: return null
            val dv = face.groupValues[4].toDoubleOrNull() ?: return null
            minU = minOf(minU, u, u + du)
            minV = minOf(minV, v, v + dv)
            maxU = maxOf(maxU, u, u + du)
            maxV = maxOf(maxV, v, v + dv)
        }
        val roundingTolerance = if (allowRoundedUvOverflow) UV_BOUNDARY_ROUNDING_TOLERANCE else 0.0
        if (width <= 0 || height <= 0 || minU < 0.0 || minV < 0.0 ||
            maxU > width + roundingTolerance || maxV > height + roundingTolerance
        ) return null
        return GeometryInfo(path, identifier, width, height, minU, minV, maxU, maxV)
    }

    private fun simpleStringMap(text: String, property: String): Map<String, String>? {
        val propertyRegex = Regex("\\\"${Regex.escape(property)}\\\"\\s*:\\s*\\{([^{}]*)\\}")
        val body = propertyRegex.findAll(text).toList().singleOrNull()?.groupValues?.get(1) ?: return null
        val pairs = MAP_STRING_PAIR.findAll(body).toList()
        if (body.isNotBlank() && MAP_STRING_PAIR.replace(body, "").replace(",", "").isNotBlank()) return null
        val result = LinkedHashMap<String, String>()
        for (pair in pairs) {
            if (result.put(pair.groupValues[1], pair.groupValues[2]) != null) return null
        }
        return result
    }

    private fun <T> geometryComponents(
        identifiers: Collection<String>,
        geometriesByTexture: Map<T, Set<String>>,
    ): List<Set<String>> {
        val parent = identifiers.associateWith { it }.toMutableMap()
        for (geometryIds in geometriesByTexture.values) {
            val linked = geometryIds.filter(parent::containsKey)
            val first = linked.firstOrNull() ?: continue
            for (identifier in linked.drop(1)) {
                val firstRoot = findGeometryRoot(parent, first)
                val linkedRoot = findGeometryRoot(parent, identifier)
                if (firstRoot != linkedRoot) parent[linkedRoot] = firstRoot
            }
        }
        return identifiers.groupBy { findGeometryRoot(parent, it) }.values.map { it.toSet() }
    }

    private fun findGeometryRoot(parent: MutableMap<String, String>, identifier: String): String {
        val root = parent.getValue(identifier)
        if (root == identifier) return root
        val resolved = findGeometryRoot(parent, root)
        parent[identifier] = resolved
        return resolved
    }

    private fun linkedGeometryTextures(
        geometryMap: Map<String, String>,
        textureMap: Map<String, String>,
        materialMap: Map<String, String>?,
        renderControllers: Set<String> = emptySet(),
        geometryTextureAliasesByController: Map<String, List<GeometryTextureAliases>?> = emptyMap(),
    ): List<Pair<String, String>>? {
        if (renderControllers.any(geometryTextureAliasesByController::containsKey)) {
            if (renderControllers.any { it !in geometryTextureAliasesByController }) return null
            val links = ArrayList<Pair<String, String>>()
            for (controller in renderControllers) {
                for (aliases in geometryTextureAliasesByController[controller] ?: return null) {
                    if (aliases.texture.equals("enchanted", ignoreCase = true) ||
                        materialMap?.get(aliases.texture)?.contains("glint", ignoreCase = true) == true
                    ) continue
                    val geometry = geometryMap[aliases.geometry] ?: return null
                    val texture = textureMap[aliases.texture] ?: return null
                    links += geometry to texture
                }
            }
            return links.distinct().takeIf { it.isNotEmpty() }
        }
        val legacyLinks = if (geometryMap.size == 1 && materialMap != null) {
            val identifier = geometryMap.values.single()
            textureMap.filterKeys { alias ->
                !alias.equals("enchanted", ignoreCase = true) &&
                    materialMap[alias]?.contains("glint", ignoreCase = true) != true
            }.values.map { identifier to it }
        } else {
            geometryMap.mapNotNull { (alias, identifier) ->
                textureMap[alias]?.let { identifier to it }
            }
        }
        if (renderControllers.isNotEmpty() && legacyLinks.isEmpty()) return null
        return legacyLinks
    }

    private fun staticGeometryTextureAliases(body: String): List<GeometryTextureAliases>? {
        val geometryValue = CONTROLLER_GEOMETRY.findAll(body).toList().singleOrNull()?.groupValues?.get(1)
            ?: return null
        fun aliases(values: List<String>, prefix: String): List<String>? {
            val result = ArrayList<String>(values.size)
            for (value in values) {
                val alias = value.removePrefix(prefix)
                if (!value.startsWith(prefix) || !CONTROLLER_ALIAS.matches(alias)) return null
                result += alias
            }
            return result.takeIf { it.isNotEmpty() }
        }
        val arrays = HashMap<String, List<String>>()
        for (match in CONTROLLER_ARRAY_DEFINITION.findAll(body)) {
            val values = JsonStringScanner.read(match.groupValues[2]) ?: return null
            if (values.isEmpty() || arrays.put(match.groupValues[1], values) != null) return null
        }
        val geometryAlias = geometryValue.removePrefix("Geometry.")
        val geometryArray = controllerArraySelection(geometryValue)
        val geometryAliases: List<String>
        val geometryIndex: String?
        if (geometryValue.startsWith("Geometry.") && CONTROLLER_ALIAS.matches(geometryAlias)) {
            geometryAliases = listOf(geometryAlias)
            geometryIndex = null
        } else {
            val selection = geometryArray ?: return null
            geometryAliases = aliases(arrays[selection.first] ?: return null, "Geometry.") ?: return null
            geometryIndex = selection.second
        }

        val textureValues = jsonStringArrayProperty(body, "textures") ?: return null
        val links = ArrayList<GeometryTextureAliases>()
        for (value in textureValues) {
            val textureAlias = value.removePrefix("Texture.")
            if (value.startsWith("Texture.") && CONTROLLER_ALIAS.matches(textureAlias)) {
                geometryAliases.forEach { links += GeometryTextureAliases(it, textureAlias) }
                continue
            }
            val selection = controllerArraySelection(value) ?: return null
            val textureAliases = aliases(arrays[selection.first] ?: return null, "Texture.") ?: return null
            if (geometryIndex == null) {
                textureAliases.forEach { links += GeometryTextureAliases(geometryAliases.single(), it) }
            } else {
                if (geometryIndex != selection.second || geometryAliases.size != textureAliases.size) return null
                geometryAliases.indices.forEach { index ->
                    links += GeometryTextureAliases(geometryAliases[index], textureAliases[index])
                }
            }
        }
        return links.distinct().takeIf { it.isNotEmpty() }
    }

    private fun controllerArraySelection(expression: String): Pair<String, String>? {
        val value = expression.trim()
        if (!value.startsWith("Array.")) return null
        val openBracket = value.indexOf('[', "Array.".length)
        if (openBracket <= "Array.".length || !value.endsWith(']')) return null
        val name = value.substring("Array.".length, openBracket)
        val index = value.substring(openBracket + 1, value.length - 1).trim()
        if (!CONTROLLER_ALIAS.matches(name) || index.isEmpty()) return null
        return name to index
    }

    private fun mergeControllerAliases(
        target: MutableMap<String, List<GeometryTextureAliases>?>,
        source: Map<String, List<GeometryTextureAliases>?>,
    ) {
        for ((controller, aliases) in source) {
            if (!target.containsKey(controller)) target[controller] = aliases
            else if (target[controller] != aliases) target[controller] = null
        }
    }

    private fun resizedDimensions(width: Int, height: Int, maxTextureDimension: Int): Pair<Int, Int> =
        PngOptimizer.targetDimensions(width, height, maxTextureDimension)

    private fun planUvCrop(models: List<GeometryInfo>, sourceWidth: Int, sourceHeight: Int): UvCropPlan? {
        if (models.isEmpty() || models.any { it.width <= 0 || it.height <= 0 }) return null
        val left = maxOf(0L, models.minOf {
            floor(it.minU * sourceWidth / it.width).toLong() -
                ceil(TEXTURE_CROP_GUTTER * sourceWidth.toDouble() / it.width).toLong()
        }).toInt()
        val top = maxOf(0L, models.minOf {
            floor(it.minV * sourceHeight / it.height).toLong() -
                ceil(TEXTURE_CROP_GUTTER * sourceHeight.toDouble() / it.height).toLong()
        }).toInt()
        val right = minOf(sourceWidth.toLong(), models.maxOf {
            ceil(it.maxU * sourceWidth / it.width).toLong() +
                ceil(TEXTURE_CROP_GUTTER * sourceWidth.toDouble() / it.width).toLong()
        }).toInt()
        val bottom = minOf(sourceHeight.toLong(), models.maxOf {
            ceil(it.maxV * sourceHeight / it.height).toLong() +
                ceil(TEXTURE_CROP_GUTTER * sourceHeight.toDouble() / it.height).toLong()
        }).toInt()
        if (right <= left || bottom <= top) return null
        return UvCropPlan(TextureCrop(left, top, right - left, bottom - top, sourceWidth, sourceHeight))
    }

    private fun geometryTransform(
        model: GeometryInfo,
        crop: TextureCrop,
        resized: Pair<Int, Int>,
    ): GeometryTransform {
        val resizeScaleU = resized.first.toDouble() / crop.width
        val resizeScaleV = resized.second.toDouble() / crop.height
        return GeometryTransform(
            resized.first,
            resized.second,
            crop.sourceWidth.toDouble() / model.width * resizeScaleU,
            crop.sourceHeight.toDouble() / model.height * resizeScaleV,
            crop.x * resizeScaleU,
            crop.y * resizeScaleV,
        )
    }

    private fun rewriteTextureReferences(bytes: ByteArray, rewrites: Map<String, String>): ByteArray {
        if (rewrites.isEmpty()) return bytes
        val text = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)).toString()
        var updated = text
        for ((oldPath, newPath) in rewrites) {
            updated = updated.replace("\"$oldPath\"", "\"$newPath\"")
        }
        return if (updated == text) bytes else updated.toByteArray(StandardCharsets.UTF_8)
    }

    private fun textureEntryName(value: String): String? {
        val raw = value.substringBefore('#').trim()
        if (raw.isEmpty() || raw.contains('\\') || raw.startsWith('/')) return null
        val path = if (raw.startsWith("textures/")) raw else "textures/$raw"
        return when {
            path.endsWith(".png", ignoreCase = true) -> path
            path.endsWith(".tga", ignoreCase = true) || path.endsWith(".jpg", ignoreCase = true) ||
                path.endsWith(".jpeg", ignoreCase = true) -> null
            else -> "$path.png"
        }
    }

    private fun isDirectTextureOnly(texture: String, directCount: Int, references: List<String>): Boolean {
        val stem = texture.removeSuffix(".png")
        var exactCount = 0
        for (reference in references) {
            if (reference == stem || reference == texture) {
                exactCount++
            } else if (reference.contains(stem)) {
                return false
            }
        }
        return exactCount == directCount
    }

    private fun isAttachmentTextureMapOnly(
        references: List<PackSetTextureReference>,
        attachmentMapReferences: List<PackSetTextureReference>,
    ): Boolean {
        if (references.isEmpty()) return false
        return references.groupingBy { it }.eachCount() == attachmentMapReferences.groupingBy { it }.eachCount()
    }

    private fun updateGeometry(bytes: ByteArray, transform: GeometryTransform): ByteArray {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw IOException("Geometry JSON was not UTF-8", exception)
        }
        val widthUpdated = replaceSingleDimension(text, TEXTURE_WIDTH, transform.width)
        val updated = replaceSingleDimension(widthUpdated, TEXTURE_HEIGHT, transform.height)
        val rewritten = StringBuilder(updated.length)
        var copiedThrough = 0
        for (match in UV_FACE.findAll(updated)) {
            rewritten.append(updated, copiedThrough, match.range.first)
            var matchPosition = match.range.first
            for (group in 1..4) {
                val range = match.groups[group]?.range ?: throw IOException("Geometry UV could not be rewritten safely")
                val value = match.groupValues[group].toDoubleOrNull()
                    ?: throw IOException("Geometry UV was not numeric")
                rewritten.append(updated, matchPosition, range.first)
                val scale = if (group % 2 == 1) transform.uScale else transform.vScale
                val offset = when (group) {
                    1 -> transform.uOffset
                    2 -> transform.vOffset
                    else -> 0.0
                }
                rewritten.append(formatUv(value * scale - offset))
                matchPosition = range.last + 1
            }
            rewritten.append(updated, matchPosition, match.range.last + 1)
            copiedThrough = match.range.last + 1
        }
        rewritten.append(updated, copiedThrough, updated.length)
        return rewritten.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun formatUv(value: Double): String = java.math.BigDecimal.valueOf(value)
        .stripTrailingZeros().toPlainString()

    private fun replaceSingleDimension(text: String, regex: Regex, value: Int): String {
        val matches = regex.findAll(text).toList()
        if (matches.size != 1) throw IOException("Geometry dimension could not be updated safely")
        val match = matches.single()
        return text.replaceRange(match.range, match.groupValues[1] + value)
    }

    private fun isAssetReference(value: String): Boolean =
        value.startsWith("geometry.") || value.contains("geometry.") || value.contains("textures/") ||
            value.contains('/') || value.contains("model_", ignoreCase = true) || value.endsWith(".png", ignoreCase = true)

    private fun isOtherRaster(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) ||
            name.endsWith(".tga", ignoreCase = true)

    private fun isFontGlyphAtlas(name: String): Boolean = FONT_GLYPH_ATLAS_PATH.matches(name)

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun validateEntryName(name: String): String {
        require(name.isNotEmpty()) { "ZIP entry has an empty name" }
        require(!name.contains('\u0000')) { "ZIP entry contains NUL: $name" }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Absolute ZIP entry: $name" }
        require(!name.contains('\\')) { "ZIP entry uses a backslash: $name" }
        require(name.split('/').none { it == ".." }) { "Parent ZIP entry: $name" }
        return name
    }

    private fun copy(input: InputStream, output: OutputStream, buffer: ByteArray) {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private fun drain(input: InputStream, buffer: ByteArray) {
        copy(input, OutputStream.nullOutputStream(), buffer)
    }

    private fun moveIntoPlace(temp: Path, output: Path) {
        try {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class BoundedZipInputStream(input: InputStream) : ZipInputStream(input) {
        private var totalExpandedBytes = 0L
        private var entryExpandedBytes = 0L

        override fun getNextEntry(): ZipEntry? {
            val next = super.getNextEntry()
            entryExpandedBytes = 0L
            return next
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                entryExpandedBytes += count
                totalExpandedBytes += count
                if (entryExpandedBytes > MAX_ENTRY_EXPANDED_BYTES) {
                    throw IOException("ZIP entry exceeds the bounded rewrite size")
                }
                if (totalExpandedBytes > MAX_PACK_EXPANDED_BYTES) {
                    throw IOException("Pack exceeds the bounded rewrite size")
                }
            }
            return count
        }
    }

    private companion object {
        val GEOMETRY_IDENTIFIER = Regex("\\\"identifier\\\"\\s*:\\s*\\\"(geometry\\.[^\\\"\\\\]+)\\\"")
        val NORMALIZED_UVS_TRUE = Regex("\\\"normalized_uvs\\\"\\s*:\\s*true")
        val ITEM_TEXTURE_TEXTURE_VALUE = Regex("\\\"textures\\\"\\s*:\\s*\\\"(textures/items/java_rendered/[^\\\"\\\\]+)\\\"")
        val TEXTURE_WIDTH = Regex("""("texture_width"\s*:\s*)(\d+)(?=\s*[,}])""")
        val TEXTURE_HEIGHT = Regex("""("texture_height"\s*:\s*)(\d+)(?=\s*[,}])""")
        val UV_ARRAY_COUNT = Regex(""""uv"\s*:\s*\[""")
        val UV_SIZE_COUNT = Regex(""""uv_size"\s*:\s*\[""")
        val UV_FACE = Regex(
            """"uv"\s*:\s*\[\s*($UV_NUMBER)\s*,\s*($UV_NUMBER)\s*\]\s*,\s*"uv_size"\s*:\s*\[\s*($UV_NUMBER)\s*,\s*($UV_NUMBER)\s*\]"""
        )
        val MAP_STRING_PAIR = Regex("\"([^\"\\\\]+)\"\\s*:\\s*\"([^\"\\\\]*)\"")
        val RENDER_CONTROLLER_DEFINITION = Regex("\"(controller\\.render\\.[^\"\\\\]+)\"\\s*:\\s*\\{")
        val RENDER_CONTROLLER_REFERENCE = Regex("\"(controller\\.render\\.[^\"\\\\]+)\"")
        val CONTROLLER_GEOMETRY = Regex("\"geometry\"\\s*:\\s*\"([^\"\\\\]*)\"")
        val CONTROLLER_ARRAY_DEFINITION = Regex("\"Array\\.([A-Za-z0-9_]+)\"\\s*:\\s*(\\[[^\\]]*\\])")
        val CONTROLLER_ALIAS = Regex("[A-Za-z0-9_]+")
        const val ENTITY_GEOMETRY_PATH = "models/entity/"
        const val RENDER_CONTROLLERS_PATH = "render_controllers/"
        const val FLIPBOOK_TEXTURES_PATH = "textures/flipbook_textures.json"
        const val STITCHED_TEXTURE_PATH = "textures/stitched/"
        const val ITEM_TEXTURE_JSON_PATH = "textures/item_texture.json"
        const val JAVA_RENDERED_ITEM_PATH = "textures/items/java_rendered/"
        const val UV_NUMBER = "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
        // Keep small exported edge rounding eligible for resize without rewriting its UV coordinates.
        const val UV_BOUNDARY_ROUNDING_TOLERANCE = 0.00011
        const val TEXTURE_CROP_GUTTER = 4
        const val ATLAS_GUTTER = 1
        const val MAX_ATLAS_DIMENSION = 2_048
        private val FONT_GLYPH_ATLAS_PATH = Regex("^(?:font|texts/[^/]+/font)/glyph_[0-9a-f]{2}\\.png$", RegexOption.IGNORE_CASE)
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
        const val MAX_PRUNE_SCAN_BYTES = 128 * 1024 * 1024L
        const val MAX_PACK_SET_SCAN_BYTES = 256 * 1024 * 1024L
        const val MAX_PNG_BYTES = 64 * 1024 * 1024
        const val MAX_OTHER_RASTER_BYTES = 256 * 1024 * 1024
        const val MIN_FONT_GLYPH_TEXTURE_DIMENSION = 256
        const val MAX_ZIP_ENTRIES = 100_000
        const val MAX_PACK_SET_FILES = 128
        const val MAX_ENTRY_EXPANDED_BYTES = 256L * 1024 * 1024
        const val MAX_PACK_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    }
}
