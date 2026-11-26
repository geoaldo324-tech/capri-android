package io.github.emmanuelquintana.capri

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the app's screens set for her: what she calls you, and who answers her (her AI). While
 * she's on, her page owns her settings, so they go to it (host.js `settings:apply`); while she's
 * off, straight into config.json, the way her page would have saved them.
 */
object CapriSettings {
    data class Provider(val id: String, val name: String, val url: String, val keyPage: String)

    /** The services that take a key, as her setup offers them (Groq, Gemini and NVIDIA are free). */
    val KEYED = listOf(
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
        Provider("nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com/settings/api-keys"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
    )

    /** The services with models that see pictures (her look at your screen picks one of those). */
    val SEES = setOf("gemini", "openai", "groq", "openrouter", "mistral", "nvidia")

    fun name(context: Context): String = Store(context).configString("userName") ?: ""
    fun provider(context: Context): String = Store(context).configString("provider") ?: "pollinations"
    fun ollamaHost(context: Context): String = Store(context).configString("ollamaHost") ?: ""

    /** A key for this service already saved (the welcome again: no need to paste it twice). */
    fun hasKey(context: Context, provider: String): Boolean = runCatching {
        JSONObject(Store(context).read(Store.CONFIG) ?: "{}").optJSONObject("apiKeys")?.optString(provider).orEmpty().isNotEmpty()
    }.getOrDefault(false)

    /** (1.12.0, Emma: "no se ve mi clave ni que tengo registrada una de Gemini… debería verse como ofuscada") the keys
     *  saved for this service, hidden but recognizable: "AIza••••••k3Qx". */
    fun maskedKeys(context: Context, provider: String): List<String> = runCatching {
        val store = Store(context)
        val saved = JSONObject(store.read(Store.CONFIG) ?: "{}").optJSONObject("apiKeys")?.optString(provider).orEmpty()
        if (saved.isEmpty()) emptyList() else store.open(saved).split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map { k ->
            if (k.length <= 10) "••••••" else k.take(4) + "••••••" + k.takeLast(4)
        }
    }.getOrDefault(emptyList())

    /** (1.12.0) forget the keys saved for this service. */
    fun forgetKeys(context: Context, provider: String) {
        RestorePoints.create(context) // (1.13.0) a brain change: where she was is kept first
        val store = Store(context)
        val cfg = runCatching { JSONObject(store.read(Store.CONFIG) ?: "{}") }.getOrElse { JSONObject() }
        cfg.optJSONObject("apiKeys")?.remove(provider)
        if (cfg.optString("provider") == provider) cfg.put("provider", "pollinations")
        store.write(Store.CONFIG, cfg.toString())
        val live = JSONObject().put("forgetKey", provider).toString()
        CapriService.current?.let { if (it.pageReady) it.overlay?.emit("settings:apply", live) else it.queueSettings(live) }
    }

    /** (1.12.0, Emma: "por cada API key… un selector de modelos") the model you picked for this service, "" = she chooses. */
    fun model(context: Context, provider: String): String = runCatching {
        JSONObject(Store(context).read(Store.CONFIG) ?: "{}").optJSONObject("models")?.optString(provider).orEmpty()
    }.getOrDefault("")

    fun setModel(context: Context, provider: String, model: String) {
        apply(context, JSONObject().put("models", JSONObject().put(provider, model))) { cfg ->
            cfg.put("models", (cfg.optJSONObject("models") ?: JSONObject()).put(provider, model))
        }
    }

    private val NOT_CHAT = Regex("whisper|tts|speech|audio|transcri|guard|embed|moderation|image|imagen|dall|search|orpheus|playai|safeguard|rerank|reward|retriever|nvclip|parse|detector|calibration|riva|translate|deplot|diffusion|clip|ocr|\\baqa\\b|antigravity|computer-use|native-audio|-live|robotics|learnlm", RegexOption.IGNORE_CASE)

    /** The service's models to chat with, asked with your first saved key. Null if it couldn't tell. Network. */
    fun models(context: Context, provider: String): List<String>? = runCatching {
        val p = KEYED.firstOrNull { it.id == provider } ?: return null
        val store = Store(context)
        val sealed = JSONObject(store.read(Store.CONFIG) ?: "{}").optJSONObject("apiKeys")?.optString(provider).orEmpty()
        val key = store.open(sealed).split(Regex("[\\s,;]+")).firstOrNull { it.isNotBlank() } ?: return null
        val c = URL("${p.url}/models").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Capri-Android")
        c.setRequestProperty("Authorization", "Bearer $key")
        val text = try { if (c.responseCode !in 200..299) return null; c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val o = JSONObject(text)
        val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: return null
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optString("id").ifEmpty { it.optString("name") } }?.ifEmpty { null } }
            .filter { !NOT_CHAT.containsMatchIn(it) }.distinct().sortedBy { it.removePrefix("models/").lowercase() }
    }.getOrNull()

    /** Back to a service whose key is already saved: nothing to check again. */
    fun useSaved(context: Context, provider: String) {
        RestorePoints.create(context) // (1.13.0) a brain change: where she was is kept first
        apply(context, JSONObject().put("provider", provider)) { it.put("provider", provider) }
    }

    /** (1.13.0) «Regresar como estaba» (Su mente): the newest restore point, back. Through her
     *  page while she's on (it owns her memory and config; today's keys are kept), straight
     *  into the files while she's off. False when there's no point yet. */
    fun restoreLatest(context: Context): Boolean {
        val point = RestorePoints.latest(context) ?: return false
        val service = CapriService.current
        if (service != null && service.pageReady) {
            service.overlay?.emit("settings:apply", JSONObject().put("restorePoint", point).toString())
            return true
        }
        return RestorePoints.restoreDirect(context)
    }

    /** When the newest restore point was kept (ms), or null when there is none. */
    fun restoreDate(context: Context): Long? = RestorePoints.latest(context)?.optLong("at")?.takeIf { it > 0 }

    /** (1.13.0) «Platicar con otra IA», started from Su mente: her page runs the talk (duo.js).
     *  False when she isn't on — there's no talk without her. */
    fun duoStart(context: Context, provider: String, name: String, about: String): Boolean =
        tell(JSONObject().put("duoStart", JSONObject().put("provider", provider).put("name", name).put("about", about)))

    fun setName(context: Context, name: String) {
        val clean = name.trim().take(24)
        apply(context, JSONObject().put("userName", clean)) { it.put("userName", clean) }
    }

    /**
     * Her AI: checked first (a key that works, an Ollama that answers), then saved. Returns null
     * when it's set, or why not. Network: not on the main thread.
     */
    fun setAi(context: Context, provider: String, key: String = "", address: String = ""): String? {
        when (provider) {
            "pollinations" -> Unit
            "ollama" -> {
                val host = ollama(address) ?: return "?"
                val ok = runCatching { get("http://$host/api/tags", null) }.getOrElse { return it.message ?: "offline" }
                if (ok !in 200..299) return "HTTP $ok"
                RestorePoints.create(context) // (1.13.0) a brain change: where she was is kept first
                apply(context, JSONObject().put("provider", "ollama").put("url", host)) { it.put("provider", "ollama").put("ollamaHost", host) }
                return null
            }
            else -> {
                val p = KEYED.firstOrNull { it.id == provider } ?: return "?"
                // (1.11.8, Emma) several keys pasted together (commas, spaces or lines): each checked, the good
                // ones kept in that order, and when one runs out of quota her page goes on with the next
                val all = key.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
                if (all.isEmpty()) return "?"
                var why: String? = null
                val good = all.filter { one ->
                    val ok = runCatching { get("${p.url}/models", one) }.getOrElse { why = why ?: (it.message ?: "offline"); return@filter false }
                    when {
                        ok == 401 || ok == 403 -> { why = why ?: "badkey"; false }
                        ok !in 200..299 -> { why = why ?: "HTTP $ok"; false }
                        provider == "nvidia" && nvidiaKeyBad(p.url, one) -> { why = why ?: "badkey"; false }
                        else -> true
                    }
                }
                if (good.isEmpty()) return why ?: "badkey"
                // (the keys you saved before for it stay, after the new ones: one more key is one more to go on with)
                val store = Store(context)
                val before = runCatching {
                    val saved = JSONObject(store.read(Store.CONFIG) ?: "{}").optJSONObject("apiKeys")?.optString(provider).orEmpty()
                    if (saved.isEmpty()) emptyList() else store.open(saved).split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
                val k = (good + before).distinct().take(8).joinToString(",")
                val sealed = store.seal(k)
                RestorePoints.create(context) // (1.13.0) a brain change: where she was is kept first
                apply(context, JSONObject().put("provider", provider).put("key", k)) { cfg ->
                    val keys = cfg.optJSONObject("apiKeys") ?: JSONObject()
                    cfg.put("provider", provider).put("apiKeys", keys.put(provider, sealed))
                }
                return null
            }
        }
        RestorePoints.create(context) // (1.13.0) a brain change: where she was is kept first
        apply(context, JSONObject().put("provider", provider)) { it.put("provider", provider) }
        return null
    }

    /** One of her settings (size, ghost, lively): to her page if she's on, else into config.json. */
    fun set(context: Context, key: String, value: Any) {
        apply(context, JSONObject().put(key, value)) { it.put(key, value) }
    }

    /** Her look from the app's Vestuario screen: on her now if she's on (as `wardrobeLook`, since `look` there
     *  means "look at my screen"), else into config.json's `look`. */
    fun setLook(context: Context, look: JSONObject) {
        apply(context, JSONObject().put("wardrobeLook", look)) { it.put("look", look) }
    }

    /** What she remembers about you from her chats (memory.js), as she noted it down. */
    fun facts(context: Context): List<String> {
        val store = Store(context)
        val stamp = store.stamp(Store.MEMORY) // (kept while memory.json stays the same: the app's screen asks often)
        factsRead?.takeIf { it.first == stamp }?.let { return it.second }
        val list = runCatching {
            val arr = JSONObject(store.read(Store.MEMORY) ?: "{}").optJSONArray("facts")
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
        }.getOrDefault(emptyList())
        factsRead = stamp to list
        return list
    }
    @Volatile private var factsRead: Pair<Long, List<String>>? = null

    /** One of those forgotten, or all of them (null). To her page if she's on, else into memory.json. */
    fun forget(context: Context, fact: String?) {
        val service = CapriService.current
        if (service != null && service.pageReady) {
            val live = if (fact == null) JSONObject().put("forgetFacts", true) else JSONObject().put("forgetFact", fact)
            service.overlay?.emit("settings:apply", live.toString())
            return
        }
        val store = Store(context)
        val mem = runCatching { JSONObject(store.read(Store.MEMORY) ?: "{}") }.getOrElse { JSONObject() }
        val left = org.json.JSONArray()
        if (fact != null) facts(context).filter { it != fact }.forEach { left.put(it) }
        mem.put("facts", left)
        if (fact == null) {
            mem.remove("last")
            // what you'd talked about so far isn't noted down again
            val history = mem.optJSONArray("history")
            val said = history?.optJSONObject(history.length() - 1)?.optString("content").orEmpty()
            if (said.isNotEmpty()) mem.put("seen", said.take(200))
        }
        store.write(Store.MEMORY, mem.toString())
    }

    /** (1.14.0) What must never become a memory: a password, a key, a token, a card number.
     *  Checked everywhere a fact can enter (a text you teach her, the map's new memory, an
     *  edit) on this side, and in host.js/memory.js on her page's side. Raw strings: the
     *  patterns carry their own backslashes. */
    private val SECRET_WORD = Regex("""contraseñ|password|passwd|api[ -]?key|clave de|token|cvv|tarjeta de""", RegexOption.IGNORE_CASE)
    private val SECRET_NUM = Regex("""\d[\d ]{11,}\d""")
    fun looksSecret(text: String): Boolean = SECRET_WORD.containsMatchIn(text) || SECRET_NUM.containsMatchIn(text)

    /** (1.12.5) «Enseñarle un texto» (Su mente): paragraphs of a file of yours, kept as things she
     *  remembers — the same facts her AI is told and her brain on this phone quotes. To her page if
     *  she's on (it owns her memory while it lives), else straight into memory.json, like forget(). */
    fun teach(context: Context, learned: List<String>) {
        val clean = learned.filter { !looksSecret(it) } // (1.14.0) secrets never become memories
        if (clean.isEmpty()) return
        val service = CapriService.current
        if (service != null && service.pageReady) {
            service.overlay?.emit("settings:apply", JSONObject().put("teachFacts", org.json.JSONArray(clean)).toString())
            return
        }
        val store = Store(context)
        val mem = runCatching { JSONObject(store.read(Store.MEMORY) ?: "{}") }.getOrElse { JSONObject() }
        val arr = mem.optJSONArray("facts")
        val known = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
        val merged = (known + clean.filter { it !in known }).take(60) // (memory.js: at most 60 facts)
        mem.put("facts", org.json.JSONArray(merged))
        store.write(Store.MEMORY, mem.toString())
    }

    /** (1.14.0) One of her memories, rewritten where it stands (the map in Su mente, tapped):
     *  the same road as forget() — to her page if she's on, else into memory.json — keeping
     *  its place in the list. False when the new text is empty, looks like a secret, or the
     *  old memory isn't there anymore. */
    fun editFact(context: Context, from: String, to: String): Boolean {
        val clean = to.trim().replace(Regex("\\s+"), " ").take(160)
        if (clean.isEmpty() || looksSecret(clean)) return false
        val service = CapriService.current
        if (service != null && service.pageReady) {
            service.overlay?.emit("settings:apply", JSONObject().put("editFact", JSONObject().put("from", from).put("to", clean)).toString())
            return true
        }
        val store = Store(context)
        val mem = runCatching { JSONObject(store.read(Store.MEMORY) ?: "{}") }.getOrElse { JSONObject() }
        val arr = mem.optJSONArray("facts") ?: return false
        val out = org.json.JSONArray()
        var done = false
        for (i in 0 until arr.length()) {
            val f = arr.optString(i)
            if (!done && f == from) { out.put(clean); done = true } else out.put(f)
        }
        if (!done) return false
        mem.put("facts", out)
        store.write(Store.MEMORY, mem.toString())
        return true
    }

    /** (1.14.0) Her whole memory as it is kept (memory.json), readable, for the memory
     *  manager's export in Su mente: to save it or send it wherever you want. */
    fun exportMemory(context: Context): String = runCatching {
        JSONObject(Store(context).read(Store.MEMORY) ?: "{}").toString(2)
    }.getOrDefault("{}")

    /** Something only she can do right now (look at your screen): false if she isn't on. */
    fun tell(live: JSONObject): Boolean {
        val service = CapriService.current ?: return false
        if (!service.pageReady) return false
        service.overlay?.emit("settings:apply", live.toString())
        return true
    }

    /** "192.168.1.20" → "192.168.1.20:11434" (Ollama's port when none is given). */
    fun ollama(address: String): String? {
        val host = address.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (host.isEmpty()) return null
        return if (Regex(":\\d+$").containsMatchIn(host)) host else "$host:11434"
    }

    /**
     * NVIDIA lists its models to anyone, so a key is tried with a one-word question to one of its
     * chat models. True only when NVIDIA says no to the key (not when it can't tell).
     */
    private fun nvidiaKeyBad(url: String, key: String): Boolean {
        if (!key.startsWith("nvapi-")) return true
        val names = runCatching {
            val c = URL("$url/models").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            try {
                val data = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("data") ?: return false
                (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
            } finally { c.disconnect() }
        }.getOrElse { return false }
        val model = NVIDIA_TRY.firstNotNullOfOrNull { re -> names.firstOrNull { re.containsMatchIn(it) } } ?: return false
        return runCatching {
            val c = URL("$url/chat/completions").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            val body = JSONObject().put("model", model).put("max_tokens", 1)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.responseCode == 401 || c.responseCode == 403
            } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    private val NVIDIA_TRY = listOf("deepseek-v[\\d.]+-flash", "glm-[\\d.]+-flash", "gemma-4", "kimi-k2", "llama-3\\.1-nemotron-70b").map { Regex(it) }

    private fun get(url: String, key: String?): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Capri-Android")
        if (key != null) c.setRequestProperty("Authorization", "Bearer $key")
        return try { c.responseCode } finally { c.disconnect() }
    }

    /** To her page if it's up, else into config.json (merged, keeping everything else). */
    private fun apply(context: Context, live: JSONObject, saved: (JSONObject) -> Unit) {
        val service = CapriService.current
        if (service != null && service.pageReady) {
            service.overlay?.emit("settings:apply", live.toString())
            return
        }
        // (1.12.2, Emma: "cuando conectas la API… se empieza a desconfigurar la app") her page starting (or starting
        // again after a hiccup) may already hold its old copy of config.json and save it over this: it gets it too
        // once it's up
        if (service != null) service.queueSettings(live.toString())
        val store = Store(context)
        val cfg = runCatching { JSONObject(store.read(Store.CONFIG) ?: "{}") }.getOrElse { JSONObject() }
        saved(cfg)
        store.write(Store.CONFIG, cfg.toString())
    }
}
