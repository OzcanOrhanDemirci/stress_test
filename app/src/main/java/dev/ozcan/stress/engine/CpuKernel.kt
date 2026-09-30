package dev.ozcan.stress.engine

/** What one unit of a kernel's work is. */
enum class WorkUnit(val nativeName: String, val rateSymbol: String) {
    Flop("FLOP", "FLOPS"),
    Op("OP", "OPS"),
    Byte("B", "B/s"),
    Texel("TEXEL", "texel/s"),
    Pixel("PIXEL", "piksel/s");

    companion object {
        fun fromNative(name: String): WorkUnit =
            entries.firstOrNull { it.nativeName == name } ?: error("Unknown work unit '$name'")
    }
}

/**
 * One burner kernel as the native table describes it. The native table is the
 * single source of these numbers; the app never keeps its own copy.
 */
data class CpuKernel(
    val index: Int,
    val key: String,
    val code: String,
    val unit: WorkUnit,
    val opsPerIteration: Double,
    val bufferBytes: Int,
) {
    companion object {
        /** Parses one line of [NativeBridge.kernelTable]: `key|code|unit|opsPerIteration|bufferBytes`. */
        fun parse(index: Int, line: String): CpuKernel {
            val parts = line.split('|')
            require(parts.size == 5) { "Malformed kernel line '$line'" }
            return CpuKernel(
                index = index,
                key = parts[0],
                code = parts[1],
                unit = WorkUnit.fromNative(parts[2]),
                opsPerIteration = parts[3].toDouble(),
                bufferBytes = parts[4].toInt(),
            )
        }

        fun parseTable(lines: Array<String>): List<CpuKernel> = lines.mapIndexed(::parse)
    }
}
