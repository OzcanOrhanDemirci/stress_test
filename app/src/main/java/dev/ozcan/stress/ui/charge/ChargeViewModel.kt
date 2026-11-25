package dev.ozcan.stress.ui.charge

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.charge.ChargeSession
import dev.ozcan.stress.charge.ChargeTick
import dev.ozcan.stress.charge.ChargeUpdate
import dev.ozcan.stress.charge.record
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.StressDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ChargeRunState(
    val update: ChargeUpdate = ChargeUpdate(),
    val remainingMillis: Long? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val recordId: String? = null,
    /** Stopped before the first reading (still waiting for the charger): nothing was saved. */
    val discarded: Boolean = false,
)

internal class ChargeViewModel(private val graph: AppGraph, private val duration: StressDuration) : ViewModel() {
    private val _state = MutableStateFlow(ChargeRunState())
    val state = _state.asStateFlow()
    private val stop = CompletableDeferred<EndReason>()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val session = ChargeSession(duration.seconds)
            val sampling = graph.sampler.beginChargeSampling()
            var end = EndReason.Interrupted
            try {
                end = coroutineScope {
                    val samples = sampling.latest.filterNotNull().produceIn(this)
                    try {
                        var origin: Long? = null
                        var finished: EndReason? = null
                        while (finished == null && !session.update.cannotMeasure) {
                            val selected = withTimeoutOrNull(5_000) {
                                select<EndReason?> {
                                    stop.onAwait { it }
                                    samples.onReceive { sample ->
                                        val first = origin ?: sample.timeNanos.also { origin = it }
                                        val update = session.read(ChargeTick(Power.seconds(sample.timeNanos, first), sample.battery))
                                        _state.value = ChargeRunState(update, sample.remainingMillis)
                                        update.end
                                    }
                                }.let { Selection(it) }
                            }
                            finished = if (selected == null) EndReason.SamplerLost else selected.reason
                        }
                        finished ?: EndReason.ChargeReadingLost
                    } finally {
                        samples.cancel()
                    }
                }
            } finally {
                graph.sampler.endChargeSampling(sampling)
                if (session.update.alreadyFull) {
                    // Started at full: nothing was charged, so nothing is kept; the screen says why.
                    _state.value = _state.value.copy(update = session.update)
                } else if (!session.update.cannotMeasure && !session.measured) {
                    // A 0:00 record with no reading would only clutter History.
                    _state.value = _state.value.copy(discarded = true)
                } else if (!session.update.cannotMeasure) {
                    _state.value = _state.value.copy(saving = true)
                    val id = withContext(NonCancellable + Dispatchers.IO) {
                        val device = withTimeoutOrNull(2_000) { runCatching { graph.device.await() }.getOrNull() }
                        val record = session.record(startedAt, end, duration.seconds, graph.appVersion, device)
                        val saved = runCatching { graph.runs.save(record) }
                            .onFailure { Log.e("stress-charge", "Could not save charge run", it) }
                            .isSuccess
                        // While it charges nobody watches the screen: say so where it will be heard.
                        if (saved && session.reachedFull) graph.chargeNotifier.batteryFull(record)
                        record.id.takeIf { saved }
                    }
                    _state.value = _state.value.copy(saving = false, saveFailed = id == null, recordId = id)
                }
            }
        }
    }

    fun stop(reason: EndReason = EndReason.User) {
        stop.complete(reason)
    }

    private data class Selection(val reason: EndReason?)
}
