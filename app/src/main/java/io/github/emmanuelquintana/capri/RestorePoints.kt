package io.github.emmanuelquintana.capri

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * (1.13.0) Restore points, from Aldo's Universal AI Engine: before her brain changes — a cloud
 * AI picked, a key saved or forgotten, the model on this phone activated — where she was is
 * kept: her memory (memory.json, whole) and her config WITHOUT her keys (config.json minus
 * `apiKeys`; keys are sealed to this phone and restoring must never roll them back). At most
 * three points, newest first. «Regresar como estaba» in Su mente (CapriSettings.restoreLatest)
 * brings the newest one back: through her page while she's on (it owns her memory and config),
 * straight into the files while she's off (today's keys kept either way).
 */
object RestorePoints {
    private const val MAX = 3

    private fun dir(context: Context): File = File(context.filesDir, "restore-points").apply { mkdirs() }

    private fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("point-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Keep where she is, right before a brain change. A point identical to the newest isn't kept twice. */
    fun create(context: Context) {
        runCatching {
            val store = Store(context)
            val memory = store.read(Store.MEMORY) ?: return
            val config = runCatching { JSONObject(store.read(Store.CONFIG) ?: "{}") }.getOrElse { return }
            config.remove("apiKeys")
            val point = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("memory", runCatching { JSONObject(memory) }.getOrElse { return })
                .put("config", config)
            val newest = latest(context)
            if (newest != null &&
                newest.optJSONObject("memory")?.toString() == point.optJSONObject("memory")?.toString() &&
                newest.optJSONObject("config")?.toString() == point.optJSONObject("config")?.toString()
            ) return
            File(dir(context), "point-${point.optLong("at")}.json").writeText(point.toString())
            files(context).drop(MAX).forEach { it.delete() }
        }
    }

    /** The newest point: { at, memory, config } — or null when there is none yet. */
    fun latest(context: Context): JSONObject? = files(context).firstOrNull()?.let { f ->
        runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /** Restore with her page off: the files directly, today's keys kept. True when there was a point. */
    fun restoreDirect(context: Context): Boolean {
        val point = latest(context) ?: return false
        val store = Store(context)
        val memory = point.optJSONObject("memory") ?: return false
        store.write(Store.MEMORY, memory.toString())
        val config = point.optJSONObject("config") ?: return true
        runCatching {
            val now = JSONObject(store.read(Store.CONFIG) ?: "{}")
            now.optJSONObject("apiKeys")?.let { config.put("apiKeys", it) }
        }
        store.write(Store.CONFIG, config.toString())
        return true
    }
}
