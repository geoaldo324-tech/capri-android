package io.github.emmanuelquintana.capri

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * (1.13.1) Her call: a voice talk with her inside the app — NOT a phone call (no Telecom, no
 * number, nobody else). You open it, and while it's on screen she listens the whole time:
 * no name to say first, no «Capri, …». Everything you say goes the way of her app chat
 * (Ears.kt hands it to AppChat; chatvoice.js says her answers out loud), and it all lands in
 * your chat history like anything else you tell her, marked as said on a call (ChatLog).
 *
 * The phone is set up like for a call (MODE_IN_COMMUNICATION, the speaker on) and put back
 * as it was when you hang up. The mic is only open while this screen is up — its state is
 * written on the screen the whole time — and hanging up closes it at once (and stops
 * whatever she's saying). Outside the call, nothing changes: her name is still how you
 * call her, and her ears still only open with her voice switched on in Ajustes.
 */
class CallActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var stateLine: TextView
    private lateinit var micLine: TextView
    private lateinit var faceView: ImageView
    private lateinit var heardLine: TextView
    private lateinit var saidLine: TextView
    private lateinit var chrono: Chronometer
    private lateinit var muteBtn: TextView
    private val main = Handler(Looper.getMainLooper())
    private var ears: Ears? = null
    private var live = false
    private var muted = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var prevListener: ((AppChat.Reply) -> Unit)? = null
    private var prevPartial: ((String, String) -> Unit)? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Hud.INK
        window.navigationBarColor = Hud.INK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // (a call with the screen off is a call with no ears: Ears only listen with it on)
        ui = Ui(this)
        setContentView(build())
        if (!Ears.allowed(this)) {
            // her own ask, in the moment (she says the same when her voice is turned on)
            runCatching { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQ) }
                .onFailure { noMic() }
        } else begin()
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { background = Hud.Backdrop(ui.dp(3f)) }
        val col = ui.col().apply { setPadding(ui.dpi(24), ui.dpi(30), ui.dpi(24), ui.dpi(24)) }
        col.addView(ui.label(getString(R.string.call_head), Hud.GREEN).apply { gravity = Gravity.CENTER }, ui.block(0))
        // her face, big, in her cut frame
        val faceRow = ui.row().apply { gravity = Gravity.CENTER }
        faceView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Hud.Plate(Hud.INK_2, Hud.YELLOW, Hud.MANE, cut = ui.dp(12f), stroke = ui.dp(2f), offset = ui.dp(4f))
            setPadding(ui.dpi(4), ui.dpi(4), ui.dpi(8), ui.dpi(8))
            ui.face("grin")?.let { setImageBitmap(it) }
        }
        faceRow.addView(faceView, LinearLayout.LayoutParams(ui.dpi(132), ui.dpi(132)))
        col.addView(faceRow, ui.block(22))
        col.addView(ui.text("CAPRI", 30f, Hud.YELLOW, ui.head).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            includeFontPadding = false
        }, ui.block(10))
        stateLine = ui.text(getString(R.string.call_connecting).uppercase(), 13f, Hud.GREEN, ui.mono).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        col.addView(stateLine, ui.block(8))
        chrono = Chronometer(this).apply {
            typeface = ui.mono
            textSize = 15f
            setTextColor(Hud.PAPER_2)
            gravity = Gravity.CENTER
        }
        col.addView(chrono, ui.block(4))
        // the mic's state, written the whole time the call lasts
        micLine = ui.text("", 11f, Hud.GREEN, ui.mono).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }
        col.addView(micLine, ui.block(10))
        heardLine = ui.text("", 14f, Hud.PAPER_3, ui.body).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            setLineSpacing(0f, 1.15f)
        }
        col.addView(heardLine, ui.block(14))
        saidLine = ui.text("", 16f, Hud.PAPER, ui.body).apply {
            gravity = Gravity.CENTER
            maxLines = 6
            setLineSpacing(0f, 1.2f)
        }
        col.addView(saidLine, ui.block(8))
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // mute and hang up
        val btns = ui.row()
        muteBtn = ui.button(getString(R.string.call_mute), Ui.Kind.LINE) { toggleMute() }
        btns.addView(muteBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = ui.dpi(10) })
        btns.addView(ui.text("📞 " + getString(R.string.call_hangup), 17f, Hud.INK, ui.head).apply {
            gravity = Gravity.CENTER
            background = Hud.Plate(Hud.RED, Hud.RED, cut = ui.dp(8f), stroke = 0f, offset = ui.dp(3f))
            setPadding(ui.dpi(16), ui.dpi(13), ui.dpi(16), ui.dpi(13))
            isClickable = true
            setOnClickListener { finish() } // (onDestroy hangs up: mic closed, audio back, her voice stopped)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(btns)
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQ) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin() else noMic()
    }

    private fun noMic() {
        Toast.makeText(this, R.string.call_nomic, Toast.LENGTH_LONG).show()
        finish()
    }

    /** The call begins: the phone set up like for a call, her ears in call mode, her answers here. */
    private fun begin() {
        if (live) return
        // she needs a way to hear you at all: her ears only open on a phone with a speech
        // recognizer (the same gate her voice has always had, Ears.update)
        val base = Lang.current(this)
        if (!recognizerHere()) {
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        live = true
        val am = getSystemService(AudioManager::class.java)!!
        oldMode = am.mode
        @Suppress("DEPRECATION") oldSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        speaker(am, true)
        if (!CapriService.running) CapriService.start(this)
        // her answers on this screen while the call lasts (the Chat tab's own come back after:
        // they're kept and given back in onDestroy, and ChatTab takes them again when shown)
        prevListener = AppChat.listener
        prevPartial = AppChat.partialListener
        AppChat.listener = { r -> runOnUiThread { onReply(r) } }
        AppChat.partialListener = { _, text -> runOnUiThread { if (text.isNotBlank()) saidLine.text = text } }
        // her voice starts loading now, not at her first answer (as her Chat tab does)
        when (base) { "es" -> "es-MX"; "en" -> "en-US"; else -> null }?.let { runCatching { CapriNeural.warm(this, it) } }
        chrono.base = SystemClock.elapsedRealtime()
        chrono.start()
        showMic()
        attachEars(0)
    }

    private fun recognizerHere() = android.speech.SpeechRecognizer.isRecognitionAvailable(this)

    /** Her ears live in her service: a moment for it to come up, then call mode on. */
    private fun attachEars(tryN: Int) {
        if (isFinishing) return
        val e = CapriService.current?.ears
        if (e == null) {
            if (tryN < 12) main.postDelayed({ attachEars(tryN + 1) }, 300)
            else {
                Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        ears = e
        e.callState = { st -> runOnUiThread { if (!isFinishing) showState(st) } }
        e.callHeard = { said -> runOnUiThread { if (!isFinishing) heardLine.text = "«$said»" } }
        e.call(true)
    }

    private fun showState(st: String) {
        val (text, color) = when (st) {
            "speaking" -> getString(R.string.call_speaking) to Hud.YELLOW
            "thinking" -> getString(R.string.call_thinking) to Hud.CYAN
            "muted" -> getString(R.string.call_muted) to Hud.PAPER_3
            else -> getString(R.string.call_listening) to Hud.GREEN
        }
        stateLine.text = text.uppercase()
        stateLine.setTextColor(color)
        showMic()
    }

    private fun showMic() {
        micLine.text = ("● " + getString(if (muted) R.string.call_muted else R.string.call_mic_on)).uppercase()
        micLine.setTextColor(if (muted) Hud.PAPER_3 else Hud.GREEN)
    }

    private fun onReply(r: AppChat.Reply) {
        if (isFinishing) return
        saidLine.text = r.text
        ui.face(r.face)?.let { faceView.setImageBitmap(it) }
    }

    private fun toggleMute() {
        if (!live) return
        muted = !muted
        muteBtn.text = getString(if (muted) R.string.call_unmute else R.string.call_mute).uppercase()
        ears?.callMute(muted)
        showMic()
        if (muted) {
            stateLine.text = getString(R.string.call_muted).uppercase()
            stateLine.setTextColor(Hud.PAPER_3)
        }
    }

    /** Her voice (and yours) on the speaker, or back to the phone's own choice. */
    private fun speaker(am: AudioManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (dev != null && runCatching { am.setCommunicationDevice(dev) }.getOrDefault(false)) return
            } else {
                runCatching { am.clearCommunicationDevice() }
                return
            }
        }
        @Suppress("DEPRECATION") am.isSpeakerphoneOn = on
    }

    override fun onDestroy() {
        // hanging up: her call mode off (that stops her voice and closes the mic at once),
        // the Chat tab's ears on the answers back, and the phone's audio as it was
        chrono.stop()
        ears?.let {
            it.callState = null
            it.callHeard = null
            it.call(false)
        }
        if (live) {
            // (only if the call really began: before that, the Chat tab's listeners were never taken)
            AppChat.listener = prevListener
            AppChat.partialListener = prevPartial
            val am = getSystemService(AudioManager::class.java)!!
            speaker(am, false)
            if (oldSpeaker) speaker(am, true)
            am.mode = oldMode
        }
        super.onDestroy()
    }

    companion object {
        private const val MIC_REQ = 1

        fun start(from: Context) = from.startActivity(Intent(from, CallActivity::class.java))
    }
}
