package dev.ozcan.stress.telemetry

import kotlinx.coroutines.flow.StateFlow

/** What a run reads: the newest sample and the record behind it. */
interface SampleSource {
    val latest: StateFlow<Sample?>
    val log: SampleLog
}
