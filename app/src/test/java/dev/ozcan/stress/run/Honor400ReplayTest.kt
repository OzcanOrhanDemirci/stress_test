package dev.ozcan.stress.run

import dev.ozcan.stress.TestSamples.sample
import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.telemetry.CurrentFreshness
import dev.ozcan.stress.telemetry.Sample
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Honor400ReplayTest {
    @Test
    fun `archived CPU GPU and mixed loads still measure power`() {
        // The scene runs are the longest on battery and the longest overall.
        val fixtures = listOf(
            "20261001-010903/18_fp32_l2",
            "20261001-022223/17_gpu_fp32_preview",
            "20261001-022223/03_scene_100",
            "20261001-033735/06_fp32_l2_gpu_fp32_preview",
            "20261001-033342/00_scene_45",
        )
        for (name in fixtures) {
            replay(name, resource("$name.csv"), resource("$name.json"))
        }
    }

    /**
     * Opt in with STRESS_HONOR_REPLAY pointing to tools/out. The per-run CSV
     * rows are in this test's standard output in the Gradle test report.
     */
    @Test
    fun `replay all local Honor 400 sessions`() {
        val path = System.getenv("STRESS_HONOR_REPLAY")
        assumeTrue("Set STRESS_HONOR_REPLAY to the original tools/out directory", !path.isNullOrBlank())
        val root = File(requireNotNull(path))
        val files = root.walkTopDown().filter {
            it.extension == "csv" && it.parentFile?.name?.startsWith("20261001-") == true
        }.sortedBy { it.path }.toList()
        assertTrue("No Honor 400 CSVs in $root", files.isNotEmpty())
        println("session,run,old_idle_w,new_idle_w,old_idle_fresh_w,old_mean_w,new_mean_w," +
            "old_peak_w,new_peak_w,new_sustained_w,max_gap_s,cold_idle_s,load_s," +
            "min_amps,max_amps,min_mv,max_mv,counter_drops,counter_w")
        for (file in files) {
            val session = requireNotNull(file.parentFile).name
            val run = file.nameWithoutExtension
            val values = replay("$session/$run", file.readText(), file.resolveSibling("$run.json").readText())
            println((listOf(session, run) + values).joinToString(",") { it?.toString().orEmpty() })
        }
    }

    private fun replay(name: String, csv: String, json: String): List<Any?> {
        val (idle, load) = readSamples(csv)
        val old = Json.parseToJsonElement(json).jsonObject
        val oldIdle = old.getValue("idle").jsonObject.getValue("meanWatts").jsonPrimitive.doubleOrNull
        val oldMean = old.getValue("load").jsonObject.getValue("meanWatts").jsonPrimitive.doubleOrNull
        val oldPeak = old.getValue("loadMax5sWatts").jsonPrimitive.doubleOrNull
        val baseline = RunPowerBaseline(idle)
        val (summary, series) = RunAnalysis.analyze(baseline, load, emptyList()) { it.policy.toString() }
        val loadOrigin = load.first().timeNanos
        val gap = listOf(idle, load).maxOf { samples ->
            samples.zipWithNext { a, b -> Power.seconds(b.timeNanos, a.timeNanos) }.max()
        }
        val duration = Power.seconds(load.last().timeNanos, loadOrigin)
        val counters = load.mapNotNull { it.battery.chargeCounterMicroAmpHours }
        val counterDrops = counters.zipWithNext().count { (a, b) -> b < a }
        val counterWatts = Power.chargeCounterWatts(load, loadOrigin)
        val result = listOf(oldIdle, summary.idleWatts, null, oldMean, summary.meanWatts, oldPeak,
            summary.peakWatts, summary.sustainedWatts, gap, null, duration, null, null, null, null, counterDrops, counterWatts)
        if (old.getValue("pluggedDuringRun").jsonPrimitive.content == "true") {
            assertFalse(name, summary.powerValid)
            assertNull(name, summary.peakWatts)
            assertNull(name, summary.meanWatts)
            assertNull(name, summary.sustainedWatts)
            assertNull(name, summary.idleWatts)
            assertTrue(name, series.watts.all { it == null })
            return result
        }

        val convention = CurrentConvention(1e-3, -1)
        assertEquals(name, convention, baseline.convention)
        assertTrue(name, summary.powerValid)
        assertFalse(name, summary.powerFromCounter)
        closeTo("$name mean", oldMean, summary.meanWatts)
        closeTo("$name peak", oldPeak, summary.peakWatts)
        // These loads are at most 61 s, so the app's five-minute tail is the whole load.
        closeTo("$name sustained", oldMean, summary.sustainedWatts)
        assertTrue("$name load current went stale", load.all { it.battery.currentFresh })
        val all = idle + load
        val amps = all.map { convention.usableAmps(requireNotNull(it.battery.currentRaw)) }
        assertTrue("$name current outside the discharge envelope", amps.all { it != null })
        val volts = all.map { it.battery.voltageMillivolts }
        assertTrue("$name voltage outside the valid range", volts.all { it != null })
        assertEquals(name, load.size, Power.windowWatts(load, convention, loadOrigin).size)

        // The CSV starts at idle with no pre-roll. Compare the old averaging
        // method over the same fresh samples if that cold start changes idle.
        assertNotNull(name, oldIdle)
        assertNotNull(name, summary.idleWatts)
        val freshIdle = Power.watts(idle, convention, idle.first().timeNanos)
        val oldFreshIdle = Stats.meanOf(freshIdle)
        if (kotlin.math.abs(summary.idleWatts!! - oldIdle!!) > oldIdle * 0.02) {
            closeTo("$name idle after freshness warm-up", oldFreshIdle, summary.idleWatts)
        }
        return listOf(oldIdle, summary.idleWatts, oldFreshIdle, oldMean, summary.meanWatts, oldPeak, summary.peakWatts,
            summary.sustainedWatts, gap, freshIdle.first().seconds, duration, amps.filterNotNull().min(),
            amps.filterNotNull().max(), volts.filterNotNull().min(), volts.filterNotNull().max(), counterDrops, counterWatts)
    }

    private fun readSamples(csv: String): Pair<List<Sample>, List<Sample>> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
        val columns = lines.next().removePrefix("\uFEFF").split(',')
        val freshness = CurrentFreshness()
        val idle = mutableListOf<Sample>()
        val load = mutableListOf<Sample>()
        for (line in lines) {
            val fields = line.split(',')
            fun field(name: String): String = fields[columns.indexOf(name)]
            val seconds = field("t_s").toDouble()
            val raw = field("current_raw").toLongOrNull()
            val level = field("level").toInt()
            // Early files contain raw mAh. Later files already contain µAh.
            val counter = field("charge_uah").toLongOrNull()?.let { Power.normalizeChargeCounter(it, level) }
            val voltage = field("voltage_mv").toIntOrNull()?.let(Power::normalizeMillivolts)
            val plugged = field("plugged") != "0"
            val s = sample(seconds, currentRaw = raw, chargeCounter = counter, millivolts = voltage, plugged = plugged, level = level)
            val replayed = s.copy(battery = s.battery.copy(currentFresh = freshness.update(raw, !plugged, s.timeNanos)))
            when (field("phase")) {
                "idle" -> idle.add(replayed)
                "load" -> load.add(replayed)
                else -> error("Unknown lab phase in $line")
            }
        }
        return idle to load
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/honor400/$name")) { "Missing Honor 400 fixture $name" }.readText()

    private fun closeTo(label: String, old: Double?, new: Double?) {
        assertNotNull("$label old", old)
        assertNotNull("$label new", new)
        assertEquals(label, old!!, new!!, old * 0.02)
    }
}
