package dev.ozcan.stress.charge

import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val RunRecord.isCharge: Boolean get() = mode == StressMode.Charge.name

internal fun ChargeSession.record(
    startedAt: Long,
    endReason: EndReason,
    planned: Int?,
    appVersion: String,
    device: DeviceInfo?,
): RunRecord {
    val elapsed = update.elapsedSeconds.takeIf { update.phase == ChargePhase.Measuring } ?: 0.0
    val endPercent = update.battery?.levelPercent ?: points.lastOrNull()?.percent
    val analysis = ChargeAnalysis.measure(points, endPercent, elapsed)
    val safetyTemperature = update.battery?.temperatureCelsius?.takeIf { endReason == EndReason.Safety }
    val maxTemp = listOfNotNull(analysis.maxTemperature, safetyTemperature).maxOrNull()
    return RunRecord(
        id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(startedAt)),
        startedAtMillis = startedAt,
        mode = StressMode.Charge.name,
        workload = StressMode.Charge.recipe,
        plannedSeconds = planned,
        loadSeconds = elapsed,
        stoppedEarly = endReason !in listOf(EndReason.Completed, EndReason.BatteryFull),
        summary = RunSummary(
            idleWatts = update.idleWatts,
            peakWatts = analysis.peak,
            meanWatts = null,
            sustainedWatts = analysis.sustained,
            energyWattHours = analysis.energy,
            batteryStartPercent = points.firstOrNull()?.percent,
            batteryEndPercent = endPercent,
            batteryLifeHours = null,
            maxTemperatures = maxTemp?.let { mapOf(RunAnalysis.BATTERY to it) }.orEmpty(),
            startTemperatures = points.firstOrNull()?.temperature?.let { mapOf(RunAnalysis.BATTERY to it) }.orEmpty(),
            firstThrottleSeconds = emptyMap(),
            cpuStability = null,
            gpuStability = null,
            computationErrors = 0,
            powerValid = analysis.peak != null,
        ),
        series = RunSeries(
            seconds = points.map { it.seconds },
            watts = points.map { it.watts },
            batteryCelsius = points.map { it.temperature },
            batteryPercent = points.map { it.percent },
            chargeCurrentAmps = points.map { it.amps },
            batteryVolts = points.map { it.volts },
        ),
        device = device,
        safetyEnabled = true,
        endReason = endReason.name,
        safetyReason = SafetyReason.BatteryHot.name.takeIf { safetyTemperature != null },
        safetyValue = safetyTemperature,
        appVersion = appVersion,
        charge = analysis.details,
    )
}
