package dev.ozcan.stress.telemetry

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.CpuSnapshot
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.GpuSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Everything measured at one instant. */
data class Sample(
    val timeNanos: Long,
    val battery: BatteryReading,
    val sysfs: SysfsReading,
    val thermalStatus: Int,
    /** Refreshed once a second; the value between refreshes is the last one read. */
    val thermalHeadroom: Float?,
    val cpu: CpuSnapshot,
    val gpu: GpuSnapshot,
)

/** Append-only record of samples, shared between the sampler thread and readers. */
class SampleLog(private val capacity: Int = 2 * 60 * 60 * 10) {

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(sample: Sample) {
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    @Synchronized
    fun clear() = samples.clear()

    /** The last [count] samples, oldest first. */
    @Synchronized
    fun recent(count: Int): List<Sample> = samples.takeLast(count)

    /** Samples taken at or after [fromNanos] (and before [untilNanos]). */
    @Synchronized
    fun between(fromNanos: Long, untilNanos: Long = Long.MAX_VALUE): List<Sample> =
        samples.filter { it.timeNanos in fromNanos until untilNanos }
}

/**
 * Takes a [Sample] every [periodMillis] on its own thread. The thread runs at a
 * raised priority so the burner threads cannot delay it.
 */
class Sampler(
    private val battery: BatteryReader,
    private val sensors: NativeSensors,
    private val thermal: ThermalReader,
    private val cpu: CpuEngine,
    private val gpu: GpuEngine,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
) {
    private val _latest = MutableStateFlow<Sample?>(null)
    val latest: StateFlow<Sample?> = _latest.asStateFlow()

    val log = SampleLog()

    private var executor: ScheduledExecutorService? = null
    private var headroom: Float? = null
    private var headroomReadNanos: Long? = null

    @Synchronized
    fun start() {
        if (executor != null) return
        battery.start()
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                runnable.run()
            }, "stress-sampler")
        }.also { it.scheduleAtFixedRate(::tick, 0, periodMillis, TimeUnit.MILLISECONDS) }
    }

    @Synchronized
    fun stop() {
        val running = executor ?: return
        running.shutdown()
        running.awaitTermination(1, TimeUnit.SECONDS)
        executor = null
        battery.stop()
    }

    // An exception escaping a periodic task cancels every later run, so one bad
    // read must not end the sampling.
    private fun tick() {
        try {
            takeSample()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Sample failed", e)
        }
    }

    private fun takeSample() {
        val now = SystemClock.elapsedRealtimeNanos()
        val lastRead = headroomReadNanos
        if (lastRead == null || now - lastRead >= HEADROOM_INTERVAL_NANOS) {
            headroom = thermal.headroom()
            headroomReadNanos = now
        }
        val sample = Sample(
            timeNanos = now,
            battery = battery.read(now),
            sysfs = sensors.read(),
            thermalStatus = thermal.status(),
            thermalHeadroom = headroom,
            cpu = cpu.snapshot(),
            gpu = gpu.snapshot(),
        )
        log.add(sample)
        _latest.value = sample
    }

    companion object {
        const val DEFAULT_PERIOD_MILLIS = 100L
        private const val TAG = "stress-sampler"
        private const val HEADROOM_INTERVAL_NANOS = 1_000_000_000L
    }
}
