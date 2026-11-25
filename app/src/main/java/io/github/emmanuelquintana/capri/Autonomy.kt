package io.github.emmanuelquintana.capri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (1.13.0) «Ella actúa sola» — her acting on her own (autonomy.js in her page): the gate and the
 * notebook. The gate: nothing alone happens unless the master switch is on (config
 * `actsAlone`, off by default and off again every time she starts) AND the kind of action is
 * one you allowed (Su mente's button; the kinds in Ajustes: aloneApps / aloneTap / aloneScroll
 * / aloneType). The hard limits are NOT here because they're not settings at all:
 * ControlService still refuses bank and payment apps, password fields and a locked screen,
 * and acting alone it also refuses purchase-looking buttons (ControlService.actAlone).
 * The notebook: everything she does alone is written here as she does it (alone-log.json,
 * the last 100), shown in Su mente as «Lo que hizo sola». A plain file, like her chat log:
 * never part of her account's sync.
 */
object Autonomy {
    fun on(context: Context): Boolean = Store(context).configFlag("actsAlone", false)

    /** May she do this kind of thing alone? (The master switch is checked separately.) */
    fun allow(context: Context, what: String): Boolean {
        val store = Store(context)
        return when (what) {
            "launch" -> store.configFlag("aloneApps", true)
            "tap", "back", "home", "recents", "notifications", "quick", "screenshot" -> store.configFlag("aloneTap", true)
            "down", "up" -> store.configFlag("aloneScroll", true)
            "type" -> store.configFlag("aloneType", false)
            else -> false
        }
    }

    // ---------- the notebook ----------

    private fun file(context: Context): File = File(context.filesDir, "alone-log.json")

    fun log(context: Context, what: String, text: String, ok: Boolean, why: String = "") {
        runCatching {
            val all = history(context)
            val out = JSONArray()
            out.put(JSONObject().put("at", System.currentTimeMillis()).put("what", what).put("text", text).put("ok", ok).put("why", why))
            for (i in 0 until minOf(all.length(), 99)) out.put(all.optJSONObject(i))
            file(context).writeText(out.toString())
        }
    }

    /** What she did alone, newest first: { at, what, text, ok, why }. */
    fun history(context: Context): JSONArray = runCatching {
        val f = file(context)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    }.getOrDefault(JSONArray())

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
