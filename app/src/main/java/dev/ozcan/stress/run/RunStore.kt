package dev.ozcan.stress.run

import kotlinx.serialization.json.Json
import java.io.File

/** Finished runs, one JSON file each, in the app's private storage. */
class RunStore(private val dir: File) {

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
    }

    /** Every stored run, newest first. Unreadable files are skipped. */
    fun list(): List<RunRecord> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(RunRecord.serializer(), it.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAtMillis }

    fun load(id: String): RunRecord? =
        File(dir, "$id.json").takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(RunRecord.serializer(), it.readText()) }.getOrNull() }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
