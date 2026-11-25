package dev.ozcan.stress.ui.device

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.ozcan.stress.BuildConfig
import dev.ozcan.stress.R
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.telemetry.SensorAvailability
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.telemetry.ThermalReader
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors

/*
 * The device screen's engineering page as data: sections of lines, so the simple page can count them for its
 * engineering row and every missing value's reason lands once in its section's note.
 */

internal class DeviceLine(
    val key: String,
    val value: String?,
    val unit: String? = null,
    val color: Color = StressColors.Text,
    val tag: LogTag? = null,
    val source: String? = null,
    /** Why [value] is missing; collected into the section's note. */
    val reason: String? = null,
)

internal class DeviceBlock(val title: String, val summary: String?, val lines: List<DeviceLine>, extra: List<String> = emptyList()) {
    val note: String? = (extra + lines.filter { it.value == null || it.value == Format.MISSING }.mapNotNull { it.reason })
        .distinct().joinToString(" ").ifEmpty { null }
}

/** Every reading the app takes from a phone, named, and whether this phone gives it. */
@Composable
internal fun deviceReadings(live: LiveView, availability: SensorAvailability): List<Pair<String, Boolean>> {
    val context = context()
    return buildList {
        add(stringResource(R.string.device_current) to (live.currentRaw != null))
        add(stringResource(R.string.device_voltage) to (live.volts != null))
        add(stringResource(R.string.device_battery_level) to (live.levelPercent != null))
        add(stringResource(R.string.device_battery_temperature) to (live.batteryCelsius != null))
        availability.clusters.forEachIndexed { i, (_, readable) ->
            val role = live.clusters.getOrNull(i)?.role?.let { context.getString(Labels.role(it)) } ?: "${i + 1}"
            add(context.getString(R.string.device_clock_of, role) to readable)
        }
        ThermalGroup.entries.forEach { group ->
            add(context.getString(R.string.device_v2_temp_of, Labels.temperature(context, group.key)) to availability.readable(group))
        }
        add(stringResource(R.string.run_gpu_load) to availability.gpuBusy)
        add(stringResource(R.string.device_thermal_status) to (live.thermalStatus != ThermalReader.UNAVAILABLE))
        add(stringResource(R.string.device_headroom) to (live.headroom != null))
    }
}

/** The engineering page's sections, summary first and raw values last. */
@Composable
internal fun deviceLog(device: DeviceInfo?, live: LiveView?, model: DeviceViewModel): List<DeviceBlock> {
    val availability = model.availability
    return buildList {
        add(summaryBlock(device, live, availability))
        add(hardwareBlock(device, model.cpuClusters))
        if (live != null) {
            add(powerBlock(live))
            addAll(clockBlocks(live, model))
        }
        add(gpuBlock(live, availability, model.layout.gpuBusyPath))
        if (live != null) add(temperatureBlock(live, availability))
        add(zoneBlock(availability))
        if (live != null) add(rawBlock(live))
    }
}

@Composable
private fun summaryBlock(device: DeviceInfo?, live: LiveView?, availability: SensorAvailability): DeviceBlock {
    val readings = live?.let { deviceReadings(it, availability) }
    val missing = readings?.filterNot { it.second }?.map { it.first }.orEmpty()
    val fit = readings?.let { "${it.size - missing.size}/${it.size}" }
    val extra = when {
        live == null -> listOf(stringResource(R.string.device_waiting))
        missing.isNotEmpty() -> listOf(stringResource(R.string.device_v2_fit_missing, missing.joinToString(", ")))
        else -> listOf(stringResource(R.string.device_v2_fit_all))
    }
    return DeviceBlock(
        stringResource(R.string.device_v2_section_summary),
        fit,
        listOf(
            DeviceLine(
                stringResource(R.string.device_v2_readings), fit,
                color = if (missing.isEmpty()) StressColors.Good else StressColors.Warn,
                tag = if (readings == null) null else if (missing.isEmpty()) LogTag.Ok else LogTag.Warn,
            ),
            DeviceLine(
                stringResource(R.string.device_v2_refresh),
                stringResource(R.string.device_v2_refresh_value, DeviceViewModel.REFRESH_SECONDS),
            ),
        ),
        extra,
    )
}

@Composable
private fun hardwareBlock(device: DeviceInfo?, cpuClusters: List<String>): DeviceBlock {
    val title = stringResource(R.string.device_section_hardware)
    if (device == null) return DeviceBlock(title, null, emptyList(), listOf(stringResource(R.string.device_waiting_hardware)))
    val clusters = cpuClusters.ifEmpty { listOf(null) }.mapIndexed { i, label ->
        DeviceLine(
            stringResource(R.string.device_v2_cluster, i + 1), label,
            reason = stringResource(R.string.device_cpu_missing),
        )
    }
    return DeviceBlock(
        title,
        null,
        buildList {
            // Maker and model apart: one long title would squeeze its key off a narrow screen.
            add(DeviceLine(stringResource(R.string.device_v2_maker), device.manufacturer))
            add(DeviceLine(stringResource(R.string.device_v2_model), device.model))
            add(DeviceLine(stringResource(R.string.device_soc), device.soc, reason = stringResource(R.string.device_soc_missing)))
            add(DeviceLine(stringResource(R.string.device_cores), device.cpuCount.toString()))
            addAll(clusters)
            add(DeviceLine(stringResource(R.string.device_gpu), device.gpu, reason = stringResource(R.string.device_gpu_missing)))
            add(DeviceLine("Vulkan", device.vulkan, reason = stringResource(R.string.device_vulkan_missing)))
            add(DeviceLine(stringResource(R.string.device_android), device.androidVersion, unit = "API ${device.sdk}"))
            add(
                DeviceLine(
                    stringResource(R.string.device_ram), Format.number(device.ramGigabytes, 1), "GB",
                    reason = stringResource(R.string.device_ram_missing),
                ),
            )
            add(DeviceLine(stringResource(R.string.device_display), device.display, reason = stringResource(R.string.device_display_missing)))
            add(DeviceLine(stringResource(R.string.device_app), BuildConfig.VERSION_NAME))
        },
    )
}

@Composable
private fun powerBlock(live: LiveView): DeviceBlock = DeviceBlock(
    stringResource(R.string.device_section_power),
    Format.watts(live.watts).takeUnless { it == Format.MISSING },
    listOf(
        DeviceLine(
            stringResource(R.string.device_battery_output), Format.wattNumber(live.watts), "W", StressColors.Accent,
            reason = stringResource(if (live.plugged) R.string.device_power_plugged else R.string.device_power_missing),
        ),
        DeviceLine(
            stringResource(R.string.device_battery_level), Format.percentValue(live.levelPercent),
            reason = stringResource(R.string.device_level_missing),
        ),
        DeviceLine(
            stringResource(R.string.device_battery_temperature), Format.number(live.batteryCelsius, 1), "°C",
            Temperatures.battery(live.batteryCelsius),
            reason = stringResource(R.string.device_battery_temp_missing),
        ),
        DeviceLine(
            stringResource(R.string.device_current), Format.number(live.dischargeAmps, 3), "A",
            reason = stringResource(if (live.plugged) R.string.device_current_plugged else R.string.device_current_missing),
        ),
        DeviceLine(
            stringResource(R.string.device_voltage), Format.number(live.volts, 3), "V",
            reason = stringResource(R.string.device_voltage_missing),
        ),
        DeviceLine(
            stringResource(R.string.device_v2_charger),
            stringResource(if (live.plugged) R.string.device_v2_plugged else R.string.device_v2_unplugged),
            color = if (live.plugged) StressColors.Warn else StressColors.Text,
        ),
    ),
)

/** One section per CPU cluster, so each line's key stays short: now (with its sysfs file), maximum, share. */
@Composable
private fun clockBlocks(live: LiveView, model: DeviceViewModel): List<DeviceBlock> {
    val context = context()
    val clocks = stringResource(R.string.device_section_clocks)
    if (live.clusters.isEmpty()) {
        return listOf(
            DeviceBlock(
                clocks, null,
                listOf(DeviceLine(stringResource(R.string.device_cpu), null, reason = stringResource(R.string.device_clocks_missing))),
            ),
        )
    }
    return live.clusters.mapIndexed { i, cluster ->
        val readable = model.availability.clusters.getOrNull(i)?.second == true
        val (now, unit) = clockParts(cluster.khz)
        val (max, maxUnit) = clockParts(cluster.maxKhz)
        DeviceBlock(
            context.getString(R.string.title_with_part, clocks, context.getString(Labels.role(cluster.role))),
            cluster.label,
            listOf(
                DeviceLine(
                    context.getString(R.string.device_clock_now), now, unit,
                    tag = if (readable) null else LogTag.Na,
                    source = model.layout.clusters.getOrNull(i)?.currentFreqPath,
                    reason = context.getString(if (readable) R.string.device_clock_no_sample else R.string.device_clock_not_readable),
                ),
                DeviceLine(
                    context.getString(R.string.device_clock_max), max, maxUnit,
                    reason = context.getString(R.string.device_clock_max_missing),
                ),
                DeviceLine(context.getString(R.string.device_v2_clock_share), Format.percent(cluster.load?.toDouble())),
            ),
        )
    }
}

/** The GPU's load as the screens use it, then the driver's counter file and whether the app may read it. */
@Composable
private fun gpuBlock(live: LiveView?, availability: SensorAvailability, path: String?): DeviceBlock {
    val counter = stringResource(R.string.device_gpu_counter)
    val name = path?.substringAfterLast('/')
    val estimated = live != null && live.gpuBusy == null && live.gpuLoad != null
    val missingReason = stringResource(if (availability.gpuBusy) R.string.device_gpu_busy_no_sample else R.string.device_gpu_busy_missing)
    return DeviceBlock(
        stringResource(R.string.device_v2_section_counters),
        null,
        buildList {
            if (live != null) {
                add(DeviceLine(stringResource(R.string.run_gpu_load), Format.percent(live.gpuLoad), color = StressColors.Cool, reason = missingReason))
            }
            add(
                DeviceLine(
                    if (name == null) counter else stringResource(R.string.title_with_part, counter, name),
                    if (availability.gpuBusy) Format.percent(live?.gpuBusy) else null,
                    color = StressColors.Cool,
                    tag = if (availability.gpuBusy) LogTag.Ok else LogTag.Na,
                    source = path,
                    reason = missingReason,
                ),
            )
        },
        if (estimated) listOf(stringResource(R.string.device_gpu_busy_estimated)) else emptyList(),
    )
}

/** A clock split into its number and unit for a log line; kHz kept for tiny reported clocks, as [deviceClock]. */
private fun clockParts(khz: Long?): Pair<String, String?> = when {
    khz == null || khz <= 0 -> Format.MISSING to null
    khz < 1000 -> "$khz" to "kHz"
    else -> "${khz / 1000}" to "MHz"
}

@Composable
private fun temperatureBlock(live: LiveView, availability: SensorAvailability): DeviceBlock {
    val context = context()
    val lines = buildList {
        add(
            DeviceLine(
                stringResource(R.string.device_thermal_status),
                live.thermalStatus.takeUnless { it == ThermalReader.UNAVAILABLE }?.let { stringResource(Labels.thermalStatus(it)) },
                reason = stringResource(R.string.device_status_missing),
            ),
        )
        add(
            DeviceLine(
                stringResource(R.string.device_headroom), Format.number(live.headroom?.toDouble(), 2),
                reason = stringResource(R.string.device_headroom_missing),
            ),
        )
        ThermalGroup.entries.forEach { group ->
            val value = live.temperatures[group]
            val color = when (group) {
                ThermalGroup.Cpu, ThermalGroup.Gpu -> Temperatures.chip(value)
                ThermalGroup.Skin -> Temperatures.skin(value)
                else -> StressColors.Text
            }
            val reason = when {
                availability.totalZones(group) == 0 -> R.string.device_zone_missing
                !availability.readable(group) -> R.string.device_zone_not_readable
                else -> R.string.device_zone_no_sample
            }
            add(DeviceLine(Labels.temperature(context, group.key), Format.number(value, 1), "°C", color, reason = stringResource(reason)))
        }
    }
    val hottest = live.chipCelsius
    return DeviceBlock(
        stringResource(R.string.chart_temperature),
        hottest?.let { "${stringResource(R.string.temp_cpu)}/${stringResource(R.string.temp_gpu)} ${Format.celsius(it)}" },
        lines,
    )
}

@Composable
private fun zoneBlock(availability: SensorAvailability): DeviceBlock {
    val context = context()
    val zones = availability.zones
    val lines = if (zones.isEmpty()) {
        listOf(DeviceLine(stringResource(R.string.device_thermal_zones), null, reason = stringResource(R.string.device_zone_missing)))
    } else {
        zones.map { (zone, readable) ->
            DeviceLine(
                zone.type,
                Labels.temperature(context, zone.group.key),
                color = if (readable) StressColors.Text else StressColors.TextFaint,
                tag = if (readable) LogTag.Ok else LogTag.Na,
                source = zone.tempPath,
            )
        }
    }
    val extra = buildList {
        add(stringResource(R.string.device_sensors_detail))
        if (zones.any { !it.second }) add(stringResource(R.string.device_zone_not_readable))
    }
    return DeviceBlock(
        stringResource(R.string.device_thermal_zones),
        stringResource(R.string.device_v2_zones_summary, zones.count { it.second }, zones.size),
        lines,
        extra,
    )
}

@Composable
private fun rawBlock(live: LiveView): DeviceBlock {
    val window = (LiveView.WINDOW_SAMPLES - 1) * DeviceViewModel.REFRESH_SECONDS + 1
    return DeviceBlock(
        stringResource(R.string.device_v2_section_raw),
        null,
        listOf(
            DeviceLine(
                stringResource(R.string.device_current_raw), live.currentRaw?.toString(),
                reason = stringResource(R.string.device_raw_missing),
            ),
            DeviceLine(
                stringResource(R.string.device_v2_voltage_raw), live.volts?.let { Format.number(it * 1000, 0) }, "mV",
                reason = stringResource(R.string.device_voltage_missing),
            ),
            DeviceLine(
                stringResource(R.string.device_v2_window),
                stringResource(R.string.device_v2_window_value, window, DeviceViewModel.REFRESH_SECONDS),
            ),
        ),
    )
}
