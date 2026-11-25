package dev.ozcan.stress.ui.result

import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.telemetry.ThermalGroup

// The capitalised keys were written by older releases.
private val chipKeys = listOf(ThermalGroup.Cpu.key, ThermalGroup.Gpu.key, "A715", "A510", "GPU")

internal fun RunSummary.hottestChip(): Double? =
    chipKeys.mapNotNull { maxTemperatures[it] }.maxOrNull()

/** The hottest measured chip at each plotted second, preserving gaps in older or partial records. */
internal fun RunSeries.chipTemperatures(): List<Double?> {
    val chips = chipKeys.mapNotNull(temperatures::get)
    return seconds.indices.map { index -> chips.mapNotNull { it.getOrNull(index) }.maxOrNull() }
}
