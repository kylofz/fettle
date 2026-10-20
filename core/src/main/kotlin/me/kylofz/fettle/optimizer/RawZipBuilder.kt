package me.kylofz.fettle.optimizer

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Small ZIP writer that knows sizes before writing headers. Java's regular
 * ZipOutputStream emits a 16-byte data descriptor for every streamed entry;
 * large generated packs can have thousands of entries, so those descriptors
 * become real overhead.
 */
internal class RawZipBuilder(private val outputFile: Path) : Closeable {
    private data class Entry(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val offset: Long,
    )

    private val output = BufferedOutputStream(Files.newOutputStream(outputFile))
    private val entries = ArrayList<Entry>()
    private val tempDirectory = outputFile.toAbsolutePath().parent
        ?: error("ZIP output must have a parent directory")
    private var offset = 0L
    private var finished = false

    fun add(name: String, input: InputStream) = write(name, input, compress = true)

    fun addStored(name: String, input: InputStream) = write(name, input, compress = false)

    fun addStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        ByteArrayInputStream(bytes).use { input -> writeEntry(name, STORED, crc, bytes.size.toLong(), bytes.size.toLong(), input) }
    }

    private fun write(name: String, input: InputStream, compress: Boolean) {
        val raw = Files.createTempFile(tempDirectory, ".fettle-raw-", ".tmp")
        var compressed: Path? = null
        try {
            val crc = CRC32()
            var size = 0L
            Files.newOutputStream(raw).use { rawOutput ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    rawOutput.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    size += read
                }
            }

            if (compress) {
                val compressedFile = Files.createTempFile(tempDirectory, ".fettle-deflate-", ".tmp")
                compressed = compressedFile
                DeflaterOutputStream(
                    Files.newOutputStream(compressedFile),
                    Deflater(Deflater.BEST_COMPRESSION, true),
                    DEFAULT_BUFFER_SIZE,
                ).use { deflater ->
                    Files.newInputStream(raw).use { source ->
                        source.copyTo(deflater, DEFAULT_BUFFER_SIZE)
                    }
                }
            }

            val compressedSize = compressed?.let(Files::size) ?: size
            val method = if (compress && compressedSize < size) DEFLATED else STORED
            val data = if (method == DEFLATED) compressed!! else raw
            val dataSize = if (method == DEFLATED) compressedSize else size
            Files.newInputStream(data).use { source ->
                writeEntry(name, method, crc.value, dataSize, size, source)
            }
        } finally {
            Files.deleteIfExists(raw)
            compressed?.let { Files.deleteIfExists(it) }
        }
    }

    private fun writeEntry(name: String, method: Int, crc: Long, compressedSize: Long, size: Long, input: InputStream) {
        require(size <= UINT32_MAX && compressedSize <= UINT32_MAX && offset <= UINT32_MAX) {
            "ZIP64 packs are not supported: $name"
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= UINT16_MAX) { "ZIP entry name is too long: $name" }
        val localOffset = offset
        writeInt(0x04034b50)
        writeShort(20)
        writeShort(UTF8_FLAG)
        writeShort(method)
        writeShort(0)
        writeShort(0)
        writeInt(crc)
        writeInt(compressedSize)
        writeInt(size)
        writeShort(nameBytes.size)
        writeShort(0)
        output.write(nameBytes)
        outputSize(30L + nameBytes.size)
        input.copyTo(output, DEFAULT_BUFFER_SIZE)
        outputSize(compressedSize)
        entries += Entry(nameBytes, method, crc, compressedSize, size, localOffset)
    }

    fun finish() {
        if (finished) return
        val centralOffset = offset
        for (entry in entries) {
            writeInt(0x02014b50)
            writeShort(20)
            writeShort(20)
            writeShort(UTF8_FLAG)
            writeShort(entry.method)
            writeShort(0)
            writeShort(0)
            writeInt(entry.crc)
            writeInt(entry.compressedSize)
            writeInt(entry.size)
            writeShort(entry.name.size)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeInt(0)
            writeInt(entry.offset)
            output.write(entry.name)
            outputSize(46L + entry.name.size)
        }

        val centralSize = offset - centralOffset
        require(entries.size <= UINT16_MAX && centralOffset <= UINT32_MAX && centralSize <= UINT32_MAX) {
            "ZIP64 central directories are not supported"
        }
        writeInt(0x06054b50)
        writeShort(0)
        writeShort(0)
        writeShort(entries.size)
        writeShort(entries.size)
        writeInt(centralSize)
        writeInt(centralOffset)
        writeShort(0)
        output.flush()
        output.close()
        finished = true
    }

    override fun close() {
        if (!finished) output.close()
    }

    private fun outputSize(size: Long) {
        offset += size
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeInt(value: Long) {
        output.write((value and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 24) and 0xFF).toInt())
    }

    private companion object {
        const val STORED = 0
        const val DEFLATED = 8
        const val UTF8_FLAG = 0x0800
        const val UINT16_MAX = 0xFFFF
        const val UINT32_MAX = 0xFFFFFFFFL
    }
}
