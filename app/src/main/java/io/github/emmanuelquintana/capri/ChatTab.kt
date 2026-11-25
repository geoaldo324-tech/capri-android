package io.github.emmanuelquintana.capri

import android.app.Activity
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The app's center (1.11.0, Emma: "que el centro de la app sea directamente un chat con ella, para
 * chatearte con Capri cómodamente; y que en las otras opciones estén las que ya tenemos"): her chat as
 * the first tab. Your whole history with her (ChatLog: this tab and her bubble over your screen), and
 * what you write here goes to her (AppChat), with her own brain, memory and powers.
 */
class ChatTab(private val a: Activity, private val ui: Ui, private val help: () -> Unit) {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var field: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusLine: TextView
    private lateinit var headFace: ImageView
    private lateinit var input: LinearLayout
    private lateinit var aiChip: TextView
    private lateinit var duoBar: LinearLayout
    private var duoSection: LinearLayout? = null // where the live talk's turns are drawn
    private var waiting: String? = null // the id of the message she's answering
    private var typingBubble: TextView? = null
    private val dots = object : Runnable {
        override fun run() {
            val t = typingBubble ?: return
            t.text = when (t.text.length) { 1 -> "••"; 2 -> "•••"; else -> "•" }
            t.postDelayed(this, 380)
        }
    }
    private val giveUp = Runnable {
        if (waiting != null) answered(AppChat.Reply(waiting!!, a.getString(R.string.chat_noreply), "aside", false))
    }

    /** The tab: her at the top, the conversation, and the line to write in (above the pill bar). */
    fun build(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // her, at the top: face, name, how she is
        val head = ui.row().apply { setPadding(ui.dpi(20), ui.dpi(18), ui.dpi(20), ui.dpi(10)) }
        headFace = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Hud.Plate(Hud.INK_2, Hud.YELLOW, Hud.MANE, cut = ui.dp(8f), stroke = ui.dp(1.5f), offset = ui.dp(3f))
            setPadding(ui.dpi(3), ui.dpi(3), ui.dpi(6), ui.dpi(6))
            ui.face("grin")?.let { setImageBitmap(it) }
        }
        head.addView(headFace, LinearLayout.LayoutParams(ui.dpi(52), ui.dpi(52)))
        head.addView(ui.col().apply {
            setPadding(ui.dpi(12), 0, 0, 0)
            addView(ui.text("CAPRI", 26f, Hud.YELLOW, ui.head).apply { letterSpacing = 0.06f; includeFontPadding = false })
            statusLine = ui.text("", 11f, Hud.GREEN, ui.mono).apply { letterSpacing = 0.1f }
            addView(statusLine)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // (1.12.0, community: "subir archivos tipo PDF, Word…") a file for her: a PDF, a Word file, a picture, a video,
        // a song; she opens her chat over your screen and answers about it
        head.addView(ui.text("📎", 18f, Hud.YELLOW, ui.head).apply {
            gravity = Gravity.CENTER
            background = Hud.Plate(Hud.INK, Hud.YELLOW, cut = ui.dp(6f), stroke = ui.dp(1f), offset = 0f)
            contentDescription = a.getString(R.string.chat_attach)
            isClickable = true
            setOnClickListener { (a as? MainActivity)?.pickForHer() }
        }, LinearLayout.LayoutParams(ui.dpi(40), ui.dpi(40)).apply { rightMargin = ui.dpi(8) })
        // (1.12.0, community: "que en el historial de chats pudieras buscar los mensajes, como WhatsApp") your history,
        // with its search, one tap from her chat
        head.addView(ui.text("⌕", 20f, Hud.YELLOW, ui.head).apply {
            gravity = Gravity.CENTER
            background = Hud.Plate(Hud.INK, Hud.YELLOW, cut = ui.dp(6f), stroke = ui.dp(1f), offset = 0f)
            contentDescription = a.getString(R.string.you_chatlog_open)
            isClickable = true
            setOnClickListener { PageActivity.open(a, "chatlog") }
        }, LinearLayout.LayoutParams(ui.dpi(40), ui.dpi(40)).apply { rightMargin = ui.dpi(8) })
        // what you can ask her (the old «Comandos» tab)
        head.addView(ui.text("?", 18f, Hud.YELLOW, ui.head).apply {
            gravity = Gravity.CENTER
            background = Hud.Plate(Hud.INK, Hud.YELLOW, cut = ui.dp(6f), stroke = ui.dp(1f), offset = 0f)
            contentDescription = a.getString(R.string.chat_help)
            isClickable = true
            setOnClickListener { help() }
        }, LinearLayout.LayoutParams(ui.dpi(40), ui.dpi(40)))
        root.addView(head)
        // (1.12.0, Emma: "un select para saber si quiere que hable con la IA conectada o local") which AI answers her
        // here (the same one as her bubble chat): tap to pick another of the ones you set up
        aiChip = ui.text("", 11f, Hud.YELLOW, ui.mono).apply {
            letterSpacing = 0.08f
            setPadding(ui.dpi(10), ui.dpi(6), ui.dpi(10), ui.dpi(6))
            background = Hud.Plate(Hud.INK_2, Hud.LINE_STRONG, cut = ui.dp(6f), stroke = ui.dp(1f), offset = 0f)
            isClickable = true
            setOnClickListener { pickAi() }
        }
        root.addView(aiChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = ui.dpi(20); bottomMargin = ui.dpi(10)
        })
        root.addView(View(a).apply { setBackgroundColor(Hud.LINE_STRONG) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dpi(1)))

        list = ui.col().apply { setPadding(ui.dpi(14), ui.dpi(12), ui.dpi(14), ui.dpi(12)) }
        scroll = ScrollView(a).apply { isFillViewport = true; addView(list); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // (1.13.0) «Platicar con otra IA» is on: who's talking, and the stop — her emergency
        // brake for the talk (Duo.kt). Hidden unless a talk is live.
        duoBar = ui.row().apply {
            visibility = View.GONE
            setPadding(ui.dpi(14), ui.dpi(6), ui.dpi(14), ui.dpi(6))
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(a.getString(R.string.duo_bar), 12f, Hud.CYAN, ui.mono).apply { letterSpacing = 0.06f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ui.text(a.getString(R.string.duo_stop), 13f, Hud.INK, ui.head).apply {
                gravity = Gravity.CENTER
                background = Hud.Plate(Hud.RED, Hud.RED, cut = ui.dp(6f), stroke = 0f, offset = 0f)
                setPadding(ui.dpi(14), ui.dpi(7), ui.dpi(14), ui.dpi(7))
                isClickable = true
                setOnClickListener { Duo.stop() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.addView(duoBar)

        // the line to write in
        input = ui.row().apply {
            setPadding(ui.dpi(12), ui.dpi(8), ui.dpi(12), ui.dpi(8))
            gravity = Gravity.BOTTOM
        }
        field = EditText(a).apply {
            hint = a.getString(R.string.chat_hint)
            setHintTextColor(Hud.PAPER_3)
            setTextColor(Hud.PAPER)
            typeface = ui.body
            textSize = 16f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = Hud.Plate(Hud.INK_2, Hud.LINE_STRONG, cut = ui.dp(8f), stroke = ui.dp(1f), offset = 0f)
            setPadding(ui.dpi(12), ui.dpi(10), ui.dpi(12), ui.dpi(10))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        input.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sendBtn = ui.text("➤", 20f, Hud.INK, ui.head).apply {
            gravity = Gravity.CENTER
            background = Hud.Plate(Hud.YELLOW, Hud.MANE, Hud.MANE, cut = ui.dp(8f), stroke = 0f, offset = ui.dp(3f))
            contentDescription = a.getString(R.string.chat_send)
            isClickable = true
            setOnClickListener { send() }
        }
        input.addView(sendBtn, LinearLayout.LayoutParams(ui.dpi(48), ui.dpi(46)).apply { leftMargin = ui.dpi(8) })
        root.addView(input)
        return root
    }

    /** Room under the line to write in: the pill bar, or the keyboard (then the bar is hidden). */
    fun setBottom(px: Int) = input.setPadding(ui.dpi(12), ui.dpi(8), ui.dpi(12), px)

    fun onShow() {
        if (waiting == null) load()
        AppChat.listener = { answered(it) }
        AppChat.partialListener = { id, text -> partial(id, text) }
        // (1.13.0) her talks with another AI, drawn live, and the photos she takes when you ask
        Duo.listener = { duoDraw() }
        AppChat.photoListener = { photoBubble(it) }
        duoDraw()
        Ears.voiceListener = { onVoice(it) }
        status()
        showAi()
        // (1.12.0) her voice starts loading as you open her chat, not when you send (it takes a few seconds)
        when (Lang.current(a)) { "es" -> "es-MX"; "en" -> "en-US"; else -> null }?.let { runCatching { CapriNeural.warm(a, it) } }
    }

    // ---- (1.12.0) which AI answers her ----
    private fun aiName(id: String): String = when (id) {
        "pollinations" -> a.getString(R.string.chat_ai_free)
        "local" -> a.getString(R.string.main_ai_local) // (1.12.5) her own lines, no internet
        "litert" -> a.getString(R.string.ai_litert) // (1.13.0) the downloaded model on this phone
        "ollama" -> a.getString(R.string.chat_ai_local, CapriSettings.ollamaHost(a).ifEmpty { "Ollama" })
        else -> CapriSettings.KEYED.firstOrNull { it.id == id }?.name ?: id
    }

    private fun showAi() {
        if (!::aiChip.isInitialized) return
        aiChip.text = a.getString(R.string.chat_ai, aiName(CapriSettings.provider(a))).uppercase() + "  ▾"
    }

    private fun pickAi() {
        val ids = CapriSettings.KEYED.filter { CapriSettings.hasKey(a, it.id) }.map { it.id }.toMutableList()
        ids += "pollinations"
        ids += "local" // (1.12.5) always there: her own lines, no internet
        if (LiteRtBrain.active(a) != null) ids += "litert" // (1.13.0) there once a model is downloaded
        val local = CapriSettings.ollamaHost(a).isNotEmpty()
        if (local) ids += "ollama"
        val now = CapriSettings.provider(a)
        val labels = ids.map { (if (it == now) "● " else "") + aiName(it) }.toMutableList()
        if (!local) labels += a.getString(R.string.chat_ai_nolocal)
        // (1.12.0, Emma: "este menú hazlo personalizado") in her own look, like the rest of the app, not Android's dialog
        val dialog = android.app.Dialog(a)
        val card = ui.card(Hud.YELLOW, Hud.MANE).apply {
            addView(ui.label(a.getString(R.string.chat_ai_pick)))
            labels.forEachIndexed { i, label ->
                val id = ids.getOrNull(i)
                val kind = if (id == now) Ui.Kind.GREEN else Ui.Kind.LINE
                addView(ui.button(label, kind) {
                    dialog.dismiss()
                    if (id == null) return@button Toast.makeText(a, R.string.chat_ai_nolocal_how, Toast.LENGTH_LONG).show()
                    if (id == now) return@button
                    Thread { CapriSettings.useSaved(a, id); a.runOnUiThread { showAi() } }.start()
                }, ui.block(if (i == 0) 12 else 8))
            }
            addView(ui.button(a.getString(android.R.string.cancel), Ui.Kind.LINE) { dialog.dismiss() }.apply { alpha = 0.7f }, ui.block(14))
        }
        val pad = android.widget.FrameLayout(a).apply { setPadding(ui.dpi(4), ui.dpi(4), ui.dpi(10), ui.dpi(10)); addView(card) }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(pad)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    // ---- (1.11.8) her voice for each answer: a small line under it with how much is made, then "speaking" ----
    // Emma: "que aparezca el mensaje de respuesta pero que haya un indicador que diga el porcentaje de generación
    // de voz de ese mensaje para que no se sienta la conversación muy lenta". The answer shows at once; its voice
    // (appchat.js says it with the id "app-<your message's id>") reports here through Ears.voiceListener.
    private val voiceLines = HashMap<String, TextView>()
    private val voiceLast = HashMap<String, org.json.JSONObject>()

    private fun onVoice(ev: org.json.JSONObject) {
        val id = ev.optString("id")
        if (!id.startsWith("app-")) return
        voiceLast[id] = ev
        voiceLines[id]?.let { showVoice(it, ev) }
    }

    // (1.12.0, Emma: "un color específico cuando la voz la genera Fish y cuando la genera local") which voice made it
    private val voiceSrc = HashMap<TextView, String>()
    private fun srcColor(src: String?) = when (src) {
        FishVoice.ID -> Hud.CYAN
        CapriNeural.ID -> Hud.GREEN
        "clip" -> Hud.YELLOW
        "system" -> Hud.PAPER_2
        else -> Hud.PAPER_3
    }
    private fun srcName(src: String?) = when (src) {
        FishVoice.ID -> a.getString(R.string.voice_src_fish)
        CapriNeural.ID -> a.getString(R.string.voice_src_local)
        "clip" -> a.getString(R.string.voice_src_clip)
        "system" -> a.getString(R.string.voice_src_system)
        else -> null
    }

    private fun showVoice(line: TextView, ev: org.json.JSONObject) {
        val pct = if (ev.has("pct")) ev.optInt("pct") else -1
        val state = ev.optString("state")
        if (state == "VOICE_UNAVAILABLE" && ev.optString("source") == FishVoice.ID) { voiceSrc.remove(line); return } // (her other voice takes over)
        ev.optString("source").takeIf { srcName(it) != null }?.let { voiceSrc[line] = it }
        val text = when (state) {
            "VOICE_REQUESTED" -> if (line.text.isNullOrEmpty()) a.getString(R.string.chat_voice_prep) else null
            "VOICE_PREPARING" -> a.getString(R.string.chat_voice_prep)
            "VOICE_PROGRESS" -> when {
                pct < 0 -> null
                ev.optBoolean("generating", true) && line.tag == "talking" -> a.getString(R.string.chat_voice_talk_pct, pct)
                ev.optBoolean("generating", true) -> a.getString(R.string.chat_voice_gen, pct)
                line.tag == "talking" -> a.getString(R.string.chat_voice_talk)
                else -> null
            }
            "VOICE_READY", "VOICE_STARTED" -> {
                line.tag = "talking"
                if (ev.optString("source") == CapriNeural.ID && pct in 0..99) a.getString(R.string.chat_voice_talk_pct, pct)
                else a.getString(R.string.chat_voice_talk)
            }
            "VOICE_FINISHED", "VOICE_CANCELLED", "VOICE_ERROR", "VOICE_UNAVAILABLE" -> {
                line.visibility = View.GONE
                voiceSrc.remove(line)
                return
            }
            else -> null
        } ?: return
        val src = voiceSrc[line]
        line.setTextColor(srcColor(src))
        line.text = srcName(src)?.let { "$text · $it" } ?: text
        line.visibility = View.VISIBLE
    }

    fun status() {
        val on = CapriService.running
        statusLine.text = a.getString(if (waiting != null) R.string.chat_typing else if (on) R.string.chat_online else R.string.chat_asleep).uppercase()
        statusLine.setTextColor(if (on || waiting != null) Hud.GREEN else Hud.PAPER_3)
    }

    /** Your history with her, the last of it (bubble chats over your screen too). */
    private fun load() {
        list.removeAllViews()
        val log = ChatLog.all(a)
        if (log.length() == 0) bubble(a.getString(R.string.chat_hello, CapriSettings.name(a).ifBlank { a.getString(R.string.chat_you) }).replace(", .", "."), false, "grin")
        var lastDay = ""
        val day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        for (i in maxOf(0, log.length() - 80) until log.length()) {
            val e = log.optJSONObject(i) ?: continue
            val d = day.format(java.util.Date(e.optLong("at")))
            if (d != lastDay) { lastDay = d; dayLine(d) }
            val q = e.optString("q").trim()
            if (q.isNotEmpty()) bubble(q, true, null)
            bubble(e.optString("a"), false, null)
        }
        duoDraw()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- (1.13.0) «Platicar con otra IA», live in this chat (Duo.kt) ----

    /** The talk's turns under the history: hers as hers, the guest's in cyan with its name. */
    private fun duoDraw() {
        if (!::list.isInitialized) return
        duoBar.visibility = if (Duo.active) View.VISIBLE else View.GONE
        duoSection?.let { list.removeView(it) }
        duoSection = null
        val turns = Duo.turns()
        if (turns.isEmpty()) return
        val box = ui.col()
        box.addView(ui.text("// " + a.getString(R.string.duo_head).uppercase(), 10f, Hud.CYAN, ui.mono).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }, ui.block(10).apply { bottomMargin = ui.dpi(2) })
        for (t in turns) when (t.who) {
            "guest" -> guestBubble(t.name, t.text, box)
            "user" -> bubble(t.text, true, null, box)
            "note" -> box.addView(ui.text(t.text, 12f, Hud.PAPER_3, ui.mono).apply { gravity = Gravity.CENTER }, ui.block(8))
            else -> bubble(t.text, false, null, box)
        }
        list.addView(box)
        duoSection = box
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun guestBubble(name: String, text: String, into: LinearLayout) {
        val col = ui.col()
        col.addView(ui.text(name.uppercase(), 10f, Hud.CYAN, ui.mono).apply { letterSpacing = 0.1f; setPadding(ui.dpi(36), 0, 0, ui.dpi(2)) })
        val row = ui.row().apply { gravity = Gravity.START or Gravity.BOTTOM }
        row.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            ui.face("look")?.let { setImageBitmap(it) }
        }, LinearLayout.LayoutParams(ui.dpi(30), ui.dpi(30)).apply { rightMargin = ui.dpi(6) })
        row.addView(ui.text(text, 15f, Hud.PAPER, ui.body).apply {
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            background = Hud.Plate(Hud.INK_2, Hud.CYAN, cut = ui.dp(8f), stroke = ui.dp(1f), offset = 0f)
            setPadding(ui.dpi(12), ui.dpi(9), ui.dpi(12), ui.dpi(10))
            maxWidth = (a.resources.displayMetrics.widthPixels * 0.74).toInt()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(row)
        into.addView(col, ui.block(8))
    }

    /** (1.13.0) A photo she took because you asked: in the conversation, her side. */
    private fun photoBubble(base64Jpeg: String) {
        if (!::list.isInitialized) return
        val bmp = runCatching {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return
        val row = ui.row().apply { gravity = Gravity.START or Gravity.BOTTOM }
        row.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            ui.face("look")?.let { setImageBitmap(it) }
        }, LinearLayout.LayoutParams(ui.dpi(30), ui.dpi(30)).apply { rightMargin = ui.dpi(6) })
        val w = (a.resources.displayMetrics.widthPixels * 0.62).toInt()
        val h = if (bmp.width > 0) (w.toLong() * bmp.height / bmp.width).toInt().coerceAtMost(ui.dpi(380)) else w
        row.addView(ImageView(a).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Hud.Plate(Hud.INK_2, Hud.MANE, cut = ui.dp(8f), stroke = ui.dp(1f), offset = 0f)
            setPadding(ui.dpi(4), ui.dpi(4), ui.dpi(4), ui.dpi(4))
        }, LinearLayout.LayoutParams(w, h))
        list.addView(row, ui.block(8))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dayLine(d: String) {
        list.addView(ui.text("// ${d.uppercase()}", 10f, Hud.PAPER_3, ui.mono).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }, ui.block(10).apply { bottomMargin = ui.dpi(2) })
    }

    private fun bubble(text: String, mine: Boolean, face: String?, into: LinearLayout = list): TextView {
        val row = ui.row().apply { gravity = (if (mine) Gravity.END else Gravity.START) or Gravity.BOTTOM }
        if (!mine) row.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            (face?.let { ui.face(it) } ?: ui.face("smug"))?.let { setImageBitmap(it) }
        }, LinearLayout.LayoutParams(ui.dpi(30), ui.dpi(30)).apply { rightMargin = ui.dpi(6) })
        val t = ui.text(text, 15f, if (mine) Hud.INK else Hud.PAPER, ui.body).apply {
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            background = if (mine) Hud.Plate(Hud.YELLOW, Hud.YELLOW, cut = ui.dp(8f), stroke = 0f, offset = 0f)
            else Hud.Plate(Hud.INK_2, Hud.MANE, cut = ui.dp(8f), stroke = ui.dp(1f), offset = 0f)
            setPadding(ui.dpi(12), ui.dpi(9), ui.dpi(12), ui.dpi(10))
            maxWidth = (a.resources.displayMetrics.widthPixels * 0.74).toInt()
        }
        row.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // (1.12.0, Emma) your picture next to yours, like hers next to hers
        if (mine) row.addView(Profile.Avatar(a), LinearLayout.LayoutParams(ui.dpi(30), ui.dpi(30)).apply { leftMargin = ui.dpi(6) })
        into.addView(row, ui.block(8))
        return t
    }

    private fun send() {
        val text = field.text.toString().trim()
        if (text.isEmpty() || waiting != null) return
        // (1.13.0) a talk with another AI is live: what you write goes to both of them (Duo.kt)
        if (Duo.active) {
            field.setText("")
            Duo.say(a, text)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        // Emma's beta code (Beta.kt): checked quietly, never shown, never sent to the AI
        if (Beta.looksLikeCode(text)) {
            field.setText("")
            Threads.make {
                val why = Beta.unlock(a, text)
                a.runOnUiThread {
                    if (why == null) { Toast.makeText(a, "Canal beta activado. Míralo en Actualizaciones.", Toast.LENGTH_LONG).show(); (a as? MainActivity)?.betaChanged() }
                    else Toast.makeText(a, why, Toast.LENGTH_LONG).show()
                }
            }.start()
            return
        }
        if (!Settings.canDrawOverlays(a)) {
            Toast.makeText(a, R.string.ob_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        field.setText("")
        bubble(text, true, null)
        val wasOn = CapriService.running
        waiting = AppChat.ask(a, text)
        typingBubble = bubble("•", false, "thinking").also { it.post(dots) }
        if (!wasOn) bubble(a.getString(R.string.chat_waking), false, "wide").alpha = 0.7f
        status()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        list.removeCallbacks(giveUp)
        list.postDelayed(giveUp, 150_000)
    }

    /** (1.12.0) A file from the 📎: your bubble with its name, and her answer here when it comes (share.js). */
    fun attached(label: String): String {
        val id = System.currentTimeMillis().toString()
        if (waiting != null) return id
        bubble(label, true, null)
        waiting = id
        typingBubble = bubble("•", false, "thinking").also { it.post(dots) }
        status()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        list.removeCallbacks(giveUp)
        list.postDelayed(giveUp, 150_000)
        return id
    }

    /** (1.11.8) Her answer as it's written, in the bubble that was dots: you read it before her voice says it. */
    private fun partial(id: String, text: String) {
        if (id != waiting || text.isBlank()) return
        val tb = typingBubble ?: return
        tb.removeCallbacks(dots)
        tb.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun answered(r: AppChat.Reply) {
        if (r.id != waiting) return
        waiting = null
        list.removeCallbacks(giveUp)
        typingBubble?.let { tb ->
            tb.removeCallbacks(dots)
            tb.text = r.text
            ((tb.parent as? LinearLayout)?.getChildAt(0) as? ImageView)?.let { img -> ui.face(r.face)?.let { img.setImageBitmap(it) } }
            ui.glitchIn(tb)
            // (1.11.8) her voice's line under it (hidden until her voice says how it goes)
            val row = tb.parent as? View
            if (r.ok && row != null) {
                val id = "app-${r.id}"
                val line = ui.text("", 11f, Hud.GREEN, ui.mono).apply {
                    letterSpacing = 0.06f
                    visibility = View.GONE
                    setPadding(ui.dpi(36), 0, 0, 0)
                }
                list.addView(line, list.indexOfChild(row) + 1, ui.block(2))
                voiceLines.keys.retainAll { it == id || voiceLines[it]?.visibility == View.VISIBLE }
                voiceLines[id] = line
                voiceLast[id]?.let { showVoice(line, it) }
            }
        }
        typingBubble = null
        ui.face(r.face)?.let { headFace.setImageBitmap(it) }
        status()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
