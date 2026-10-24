package dev.ozcan.stress.run

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Finished runs, one JSON file each, in the app's private storage. */
class RunStore(private val dir: File, private val maxRuns: Int = DEFAULT_MAX_RUNS) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(record: RunRecord) {
        dir.mkdirs()
        // Write aside and rename, so a run is never left half written.
        val temp = File(dir, "${record.id}.json.tmp")
        temp.writeText(json.encodeToString(RunRecord.serializer(), record))
        check(temp.renameTo(File(dir, "${record.id}.json"))) { "Could not store run ${record.id}" }
        prune()
    }

    /**
     * Every stored run, newest first, with its watts line but without its
     * other curves: the lists and pickers need no more, and a long history's
     * temperatures and clocks are not worth decoding to draw a row. The full
     * record comes with [load]. Unreadable files are skipped.
     */
    fun list(): List<RunRecord> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { withoutCurves(f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAtMillis }

    private fun withoutCurves(text: String): RunRecord {
        val fields = Json.parseToJsonElement(text).jsonObject
        val watts = (fields["series"] as? JsonObject)?.get("watts")
            ?: return json.decodeFromString(RunRecord.serializer(), text)
        val light = JsonObject(fields.toMutableMap().apply { put("series", JsonObject(mapOf("watts" to watts))) })
        return json.decodeFromString(RunRecord.serializer(), light.toString())
    }

    fun load(id: String): RunRecord? =
        File(dir, "$id.json").takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(RunRecord.serializer(), it.readText()) }.getOrNull() }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    /** Keeps the newest [maxRuns] files; the id's timestamp orders them. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= maxRuns) return
        files.sortedBy { it.name }.take(files.size - maxRuns).forEach { it.delete() }
    }

    private companion object {
        const val DEFAULT_MAX_RUNS = 200
    }
}
