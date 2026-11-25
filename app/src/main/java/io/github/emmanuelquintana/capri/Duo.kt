package io.github.emmanuelquintana.capri

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * (1.13.0) «Platicar con otra IA» — the native side of duo.js: the conversation itself lives in
 * her page (her brain and the guest's, turn by turn), and each turn lands here through
 * HostBridge ("duo:turn" / "duo:state") for the app's Chat tab to draw — Capri's turns as hers,
 * the guest's in their own colour with their name, yours as yours. The turns are kept here,
 * in memory only (a talk is a moment, not her history; her own memory of it is hers to note
 * down like any chat). What you write in the Chat tab while a talk is on goes to both of them
 * (say), and the tab's stop chip ends it at once (stop).
 */
object Duo {
    data class Turn(val who: String, val name: String, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val all = mutableListOf<Turn>()

    @Volatile var active = false
        private set

    /** The Chat tab redraws itself when this fires (always on the main thread). */
    @Volatile var listener: (() -> Unit)? = null

    fun turns(): List<Turn> = synchronized(lock) { all.toList() }

    /** A turn from her page: "capri" | "guest" | "user" | "note". */
    fun turn(who: String, name: String, text: String) {
        synchronized(lock) {
            all.add(Turn(who, name, text.take(2000)))
            while (all.size > 60) all.removeAt(0)
        }
        main.post { listener?.invoke() }
    }

    fun state(on: Boolean) {
        if (on) synchronized(lock) { all.clear() }
        active = on
        main.post { listener?.invoke() }
    }

    /** You, jumping in from the Chat tab: drawn here, and handed to her page for both of them. */
    fun say(context: Context, text: String) {
        turn("user", CapriSettings.name(context), text)
        CapriService.current?.overlay?.emit("duo:say", JSONObject().put("text", text).toString())
    }

    /** The stop chip: her page stops the talk; the state comes back through state(false). */
    fun stop() {
        CapriService.current?.overlay?.emit("duo:stop", "{}")
        state(false)
    }
}
