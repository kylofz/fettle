package me.kylofz.fettle.optimizer

/** Immutable result of one pack rewrite. */
public class PackOptimizationReport(
    public val inputBytes: Long,
    public val outputBytes: Long,
    public val entriesRead: Int,
    public val entriesWritten: Int,
    public val entriesFiltered: Int,
    public val jsonEntriesMinified: Int,
    public val pngEntriesOptimized: Int,
    public val otherRasterEntriesResized: Int,
) {
    public val savedBytes: Long
        get() = inputBytes - outputBytes
}
