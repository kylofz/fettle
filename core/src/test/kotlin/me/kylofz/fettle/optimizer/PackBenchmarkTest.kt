package me.kylofz.fettle.optimizer

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class PackBenchmarkTest {
    @Test
    fun safeProfileBenchmark(@TempDir work: Path) {
        val configuredPaths = System.getenv("FETTLE_BENCHMARK_PACKS")
        assumeTrue(!configuredPaths.isNullOrBlank(), "Set FETTLE_BENCHMARK_PACKS to a path-separated list of packs")

        val inputs = configuredPaths!!.split(File.pathSeparator).filter(String::isNotBlank).map(Path::of)
        val auditor = PackAuditor()
        val optimizer = PackOptimizer()
        inputs.forEachIndexed { index, input ->
            require(Files.isRegularFile(input)) { "Benchmark pack does not exist: $input" }
            val output = work.resolve("safe-$index.mcpack")
            val sourceAudit = measure { auditor.audit(input) }
            val heap = PeakHeapSampler().start()
            val optimization = measure { optimizer.optimizeSafe(input, output, 128) }
            val peakUsedHeap = heap.closeAndRead()
            val outputAudit = measure { auditor.audit(output) }
            val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val heapLimit = Runtime.getRuntime().maxMemory()

            println(
                "FETTLE_BENCHMARK profile=SAFE input=${input.fileName} " +
                    "source_audit_ms=${sourceAudit.wallMillis} source_cpu_ms=${sourceAudit.cpuMillis} " +
                    "optimize_ms=${optimization.wallMillis} optimize_cpu_ms=${optimization.cpuMillis} " +
                    "peak_sampled_heap_bytes=$peakUsedHeap max_heap_bytes=$heapLimit " +
                    "output_audit_ms=${outputAudit.wallMillis} output_audit_cpu_ms=${outputAudit.cpuMillis} " +
                    "input_bytes=${Files.size(input)} output_bytes=${Files.size(output)} " +
                    "input_texture_bytes=${sourceAudit.value.textureMemoryBytes} " +
                    "output_texture_bytes=${outputAudit.value.textureMemoryBytes} " +
                    "input_item_atlas_bytes=${sourceAudit.value.itemAtlasTextureMemoryBytes} " +
                    "output_item_atlas_bytes=${outputAudit.value.itemAtlasTextureMemoryBytes} " +
                    "input_block_atlas_bytes=${sourceAudit.value.blockAtlasTextureMemoryBytes} " +
                    "output_block_atlas_bytes=${outputAudit.value.blockAtlasTextureMemoryBytes} " +
                    "input_handles=${sourceAudit.value.estimatedTextureHandleCount} " +
                    "output_handles=${outputAudit.value.estimatedTextureHandleCount} " +
                    "input_files=${sourceAudit.value.fileCount} output_files=${outputAudit.value.fileCount} " +
                    "png_resized=${optimization.value.pngEntriesOptimized} " +
                    "other_rasters_resized=${optimization.value.otherRasterEntriesResized} " +
                    "source_texture_scan_complete=${sourceAudit.value.textureMetricsComplete} " +
                    "output_texture_scan_complete=${outputAudit.value.textureMetricsComplete} " +
                    "process_cpu_supported=${bean?.processCpuTime?.let { it >= 0 } ?: false}"
            )
            assertTrue(Files.isRegularFile(output))
        }
    }

    private fun <T> measure(block: () -> T): Measurement<T> {
        val cpuBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val cpuStart = cpuBean?.processCpuTime ?: -1L
        val start = System.nanoTime()
        val value = block()
        val elapsed = System.nanoTime() - start
        val cpuEnd = cpuBean?.processCpuTime ?: -1L
        val cpuMillis = if (cpuStart >= 0 && cpuEnd >= cpuStart) (cpuEnd - cpuStart) / 1_000_000L else -1L
        return Measurement(value, elapsed / 1_000_000L, cpuMillis)
    }

    private data class Measurement<T>(val value: T, val wallMillis: Long, val cpuMillis: Long)

    private class PeakHeapSampler {
        private val running = AtomicBoolean(true)
        private val peak = AtomicLong(usedHeapBytes())
        private val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(usedHeapBytes(), ::maxOf)
                Thread.sleep(5)
            }
        }.apply {
            name = "fettle-benchmark-heap-sampler"
            isDaemon = true
        }

        fun start(): PeakHeapSampler {
            sampler.start()
            return this
        }

        fun closeAndRead(): Long {
            peak.accumulateAndGet(usedHeapBytes(), ::maxOf)
            running.set(false)
            sampler.join()
            return peak.get()
        }

        private fun usedHeapBytes(): Long {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
}
