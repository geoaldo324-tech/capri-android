package io.github.emmanuelquintana.capri

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The page's line to Android (assets/android/native.js), as preload.js + ipcMain are on the
 * desktop. invoke(id, method, args) answers later through window.__capri.reply(id, ok, json);
 * an empty id is a call nobody waits on. Method names follow the desktop's IPC channels.
 */
class HostBridge(private val service: CapriService) {
    val store = Store(service)
    val log = Diag
    private val actions = Actions(service, store)
    private val fetch = NativeFetch { channel, json -> service.overlay?.emit(channel, json) }
    private val worker = Executors.newSingleThreadExecutor(Threads.factory)
    // her eyes wait on Android's dialog (up to a minute or so): never in the way of everything else
    private val slow = Executors.newSingleThreadExecutor(Threads.factory)

    @JavascriptInterface
    fun invoke(id: String, method: String, argsJson: String) {
        // (1.13.0) the model on this phone and her camera wait long too: same slow lane as her eyes
        (if (method.startsWith("look:") || method.startsWith("litert:") || method == "camera:shot") slow else worker).execute {
            try {
                val args: Any? = if (argsJson.isEmpty() || argsJson == "null") null else JSONArray("[$argsJson]").get(0)
                val result = handle(method, args)
                service.overlay?.reply(id, true, if (result == null || result == Unit) "null" else toJson(result))
            } catch (e: Exception) {
                if (e !is Actions.Unsupported) {
                    Log.w("capri", "$method failed: ${e.message}")
                    log.add("W", "$method: ${e.message}")
                }
                service.overlay?.reply(id, false, JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString())
            }
        }
    }

    @JavascriptInterface
    fun fetchStart(id: String, url: String, method: String, headers: String, body: String) = fetch.start(id, url, method, headers, body)

    @JavascriptInterface
    fun fetchAbort(id: String) = fetch.abort(id)

    /** Modo ligero (Perf.kt), asked by her page as it starts (compat.js), before it draws anything. */
    @JavascriptInterface
    fun lite(): Boolean = Perf.lite(service)

    /** Her picture in 2D, without WebGL (1.9.1, Perf.flat): asked as her page starts (compat.js). */
    @JavascriptInterface
    fun flat(): Boolean = Perf.flat(service)

    private fun obj(args: Any?) = args as? JSONObject ?: JSONObject()

    /** (1.13.0) A camera photo made chat-sized: at most 1024 px wide, JPEG, base64 — the same
     *  shape as her screen pictures (Eyes.kt), so her look treats it like any other picture. */
    private fun shrinkPhoto(jpeg: ByteArray): String {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IllegalStateException("camera failed")
        val scaled = if (bmp.width > 1024) android.graphics.Bitmap.createScaledBitmap(bmp, 1024, (bmp.height.toLong() * 1024 / bmp.width).toInt(), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun clipboard(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var text = ""
        android.os.Handler(service.mainLooper).post {
            runCatching {
                val cm = service.getSystemService(android.content.ClipboardManager::class.java)
                text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(service)?.toString().orEmpty()
            }.onFailure { log.add("W", "clipboard: ${it.message}") }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return text.trim()
    }

    private fun handle(method: String, args: Any?): Any? = when (method) {
        // her settings and her memory (config.js, main.js)
        // (1.12.2, Emma: "de la nada seleccionó el inglés") her language is always the app's (Ajustes › Idioma):
        // whatever else wrote config.json (a copy from your account, from the PC; her page's own old copy) doesn't change it
        "config:load" -> JSONObject(store.read(Store.CONFIG) ?: "{}").let { it.put("lang", Lang.forHer(service, it.optString("lang", "auto"))) }
        "lang:app" -> Lang.setApp(service, obj(args).optString("lang")) // her own menu's Idioma: the app's too
        "config:save" -> store.write(Store.CONFIG, obj(args).toString()).let { service.onConfig(obj(args)) }
        "memory:load" -> store.memory()
        "memory:save" -> store.write(Store.MEMORY, obj(args).toString())
        // (1.10.0) your chat history, on this phone only (ChatLog.kt, shown in the app)
        "msg:share" -> obj(args).let { actions.shareMessage(it.optString("app"), it.optString("pkg").takeIf { p -> p.isNotEmpty() && p != "null" }, it.optString("text")) }
        "saver:set" -> service.setSaving(obj(args).optBoolean("on"))
        // (1.10.0) she uses your phone for you, if you turned it on (ControlService.kt)
        "control:state" -> ControlService.instance != null
        "control:do" -> obj(args).let { a ->
            val svc = ControlService.instance
            if (svc == null) false else { svc.act(a.optString("what"), a.optString("text")); true }
        }
        "control:settings" -> runCatching {
            service.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        // (1.13.0) «Ella actúa sola»: one action on her own, through the gate (Autonomy.kt) —
        // her page decides, Android allows or refuses, and it's written down either way
        "alone:do" -> obj(args).let { a ->
            val svc = ControlService.instance
            if (svc == null) JSONObject().put("ok", false) else JSONObject().put("ok", svc.actAlone(a.optString("what"), a.optString("text")))
        }
        "alone:launch" -> obj(args).let { a ->
            if (!Autonomy.on(service) || !Autonomy.allow(service, "launch")) JSONObject().put("ok", false)
            else runCatching { actions.launch(a); Autonomy.log(service, "launch", a.optString("name"), true); JSONObject().put("ok", true) }
                .getOrElse { Autonomy.log(service, "launch", a.optString("name"), false, "failed"); JSONObject().put("ok", false) }
        }
        // (1.13.0) «Platicar con otra IA» (duo.js): its turns and its state, for the app's Chat tab
        "duo:turn" -> obj(args).let { a -> Duo.turn(a.optString("who"), a.optString("name"), a.optString("text")) }
        "duo:state" -> Duo.state(obj(args).optBoolean("on"))
        // (1.13.0) a restore point, kept before any brain change (RestorePoints.kt)
        "brain:checkpoint" -> RestorePoints.create(service).let { true }
        // (1.13.0) the model on this phone (LiteRtBrain.kt): its state, and one whole answer
        "litert:state" -> LiteRtBrain.state(service)
        "litert:chat" -> JSONObject().put("text", LiteRtBrain.chat(service, obj(args).optJSONArray("messages") ?: JSONArray()))
        // (1.13.0) one photo with her camera, only when you ask (Camera.kt): shown in the app's
        // chat too (AppChat.photo), and answered by her page like a picture shared with her
        "camera:shot" -> {
            val (code, bytes) = CameraShot.take(service)
            if (code != CameraShot.OK || bytes == null) throw IllegalStateException(if (code == CameraShot.DENIED) "camera denied" else "camera failed")
            val small = shrinkPhoto(bytes)
            AppChat.photo(small)
            JSONObject().put("image", small)
        }
        "page:open" -> obj(args).optString("page").let { if (it == "games" || it == "chatlog" || it == "neuro") PageActivity.open(service, it) }
        // (1.11.0) her sleep mode (Sleep.kt): "Capri, modo sueño / despiértame a las 7", and its state
        "sleep:start" -> Sleep.start(service, obj(args).optLong("alarm", 0L)).let { true }
        "sleep:stop" -> Sleep.stop(service) ?: JSONObject.NULL
        "sleep:state" -> JSONObject().put("on", Sleep.on(service)).put("start", Sleep.startedAt(service)).put("alarm", Sleep.alarmAt(service)).put("times", Sleep.wakes(service).length())
        "neuro:add" -> Neuro.add(service, obj(args)) // (1.11.0) a braindance she recorded for you (neuro.js)
        "appchat:reply" -> AppChat.deliver(obj(args)) // (1.11.0) her answer for the app's Chat tab
        "appchat:partial" -> AppChat.partial(obj(args)) // (1.11.8) her answer as her AI writes it
        "chatlog:add" -> obj(args).let { ChatLog.add(service, it.optString("q"), it.optString("a"), it.optLong("at", System.currentTimeMillis()), it.optInt("call") == 1) } // (1.13.1) the call's mark, when it's one
        "keys:seal" -> store.seal(obj(args).optString("value"))
        "keys:open" -> store.open(obj(args).optString("value"))
        // "Pegar" for her key box: what you copied (Android lets her read it while you type in her panel)
        "clipboard:read" -> clipboard()
        "device:info" -> actions.deviceInfo()

        // her window
        "overlay:ready" -> { service.overlay?.ready(); service.onPageReady() }
        "overlay:alive" -> service.overlay?.alive()
        "share:take" -> ShareActivity.take() ?: JSONObject.NULL
        "overlay:restart" -> service.overlay?.restart(obj(args).optString("why", "her page asked"))
        "gpu:lost" -> Perf.gpuLost(service)
        "log" -> log.add(obj(args).optString("level", "E"), obj(args).optString("text"))
        "overlay:focus" -> service.overlay?.setFocusable(obj(args).optBoolean("on"))
        "overlay:blur" -> service.overlay?.setBlur(obj(args).optBoolean("on"))
        "overlay:haptic" -> service.overlay?.haptic()
        "battery:now" -> service.batteryNow()
        "touch:regions" -> service.overlay?.setRegions(obj(args).optJSONArray("rects") ?: JSONArray(), obj(args).optDouble("dpr", 1.0))
        "notify:update" -> service.refreshNotification()
        "notify:wink" -> service.wink(obj(args).optString("line"), obj(args).optString("face"))
        "boot:set" -> Unit // read from her config by BootReceiver
        "app:quit" -> service.quit()

        // "Capri, …" (Ears.kt, listen.js): her answer out loud, the flashlight, her ears off
        "voice:say" -> service.ears?.say(obj(args).optString("text"), obj(args).optString("lang", "es-MX"), obj(args))
        // (1.11.0) Capri Voice V8: stop what she says, and her recorded phrases (CapriVoice.kt, voicelines.js)
        "voice:stop" -> service.ears?.hushVoice(obj(args).optString("id"))
        // (1.11.8) her neural voice loaded ahead, while her AI writes the answer
        "voice:warm" -> CapriNeural.warm(service, obj(args).optString("lang", "es-MX"))
        "voice:packs" -> CapriVoice.catalog(service)
        "voice:torch" -> service.ears?.torch(if (obj(args).isNull("on")) null else obj(args).optBoolean("on")) ?: JSONObject.NULL
        "voice:off" -> Ears.setEnabled(service, false)
        "voice:sound" -> service.ears?.sound()

        // her look at your screen (Eyes.kt): one picture, live on or off, how much it changed
        "look:snap" -> JSONObject().put("image", service.eyes.snap())
        "audio:record" -> service.ears?.record(obj(args).optInt("seconds", 10).coerceIn(3, 15))?.let { JSONObject().put("audio", it) } ?: JSONObject.NULL // (1.12.0) a song near you
        "look:live" -> if (obj(args).optBoolean("on")) service.eyes.startLive() else service.eyes.stopLive().let { false }
        "look:changed" -> service.eyes.changed()

        // her hooks, her sounds, links
        "hooks:copy" -> actions.copyHooksCommand(obj(args).optString("command"))
        "sounds:list" -> actions.sounds()
        "sounds:open" -> actions.toast(store.soundsDir.absolutePath)
        "open:url" -> actions.openUrl(obj(args).optString("url"))
        "music:play" -> actions.playSong(obj(args).optString("query"))
        "app:open" -> actions.openApp()

        // the phone itself
        "pc:cpu" -> actions.cpu()
        "pc:do" -> actions.pcDo(obj(args))
        "apps:launchables" -> actions.launchables()
        "apps:launch" -> actions.launch(obj(args))
        "apps:media" -> actions.media(args as? String ?: "")
        // your notifications and your music (CapriNotifications.kt), once you allow it
        "notes:allowed" -> CapriNotifications.allowed(service)
        "notes:settings" -> actions.notificationAccess()
        "media:now" -> CapriNotifications.instance?.media() ?: JSONObject()
        "media:control" -> CapriNotifications.instance?.control(obj(args).optString("action")) ?: actions.media(obj(args).optString("action").let { if (it == "toggle") "play" else it })
        "media:open" -> CapriNotifications.instance?.openPlayer() ?: false
        "note:open" -> CapriNotifications.instance?.open(obj(args).optString("key")) ?: false
        "note:reply" -> obj(args).let { a -> CapriNotifications.instance?.reply(a.optString("who"), a.optString("text").take(1000)) } ?: JSONObject.NULL // (1.12.0)

        // Android never lets an app close another one: her "close Discord" answers with this
        "apps:list" -> JSONArray()
        "apps:find" -> null
        "apps:close" -> JSONObject().put("error", "Android does not let apps close other apps")

        else -> throw Actions.Unsupported(method)
    }

    private fun toJson(v: Any): String = when (v) {
        is JSONObject, is JSONArray -> v.toString()
        is String -> JSONObject.quote(v)
        JSONObject.NULL -> "null"
        else -> v.toString()
    }

    fun shutdown() {
        fetch.shutdown()
        worker.shutdownNow()
        slow.shutdownNow()
    }
}
