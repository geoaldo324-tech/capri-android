package io.github.emmanuelquintana.capri

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * (1.13.0, EXPERIMENTAL) A real AI model ON this phone, with Google's LiteRT-LM
 * (github.com/google-ai-edge/LiteRT-LM): downloaded once from «Su cerebro neuronal» in Su
 * mente, then she thinks with no network at all — a brain like any other in host.js
 * (provider "litert", through HostBridge "litert:state" / "litert:chat"), and her first
 * fallback when a chat dies offline, above her phrase brain (local-brain.js).
 *
 * The dependency is pinned in build.gradle.kts (com.google.ai.edge.litertlm:litertlm-android:
 * 0.13.1, from Google Maven, as the official guide — developers.google.com/edge/litert-lm/
 * android — prescribes). The API used below was checked against that guide AND against the
 * 0.13.1 AAR itself (its Kotlin metadata: EngineConfig(modelPath, backend, visionBackend,
 * audioBackend, maxNumTokens, maxNumImages, cacheDir), ConversationConfig(systemInstruction:
 * Contents, initialMessages: List<Message>, samplerConfig), Message.user/model(text),
 * Conversation.sendMessage(text): Message, the answer's words in Message.contents as
 * Content.Text). One thing could NOT be checked without compiling: the AAR's classes carry
 * Kotlin metadata 2.3.0 and this project builds with Kotlin 2.0.21 — build.gradle.kts adds
 * -Xskip-metadata-version-check for it; if the build still refuses the metadata, the fix is
 * upgrading the Kotlin plugin (2.2+), not touching this file.
 *
 * The models are the small generic (any-chip, CPU) .litertlm files of Hugging Face's
 * litert-community, each verified there (repo, file name and exact size, 2026-11-25):
 * - Qwen3 0.6B: not gated, downloads as-is.
 * - The Gemmas ARE gated ("auto"): Hugging Face wants the Gemma licence accepted with an
 *   account first, so their download answers 401/403 until you do — said plainly in Su mente.
 * The engine is kept loaded between questions (loading takes seconds); a fresh conversation
 * is built from the messages each time, the way her chat's brain calls work everywhere else.
 */
object LiteRtBrain {
    data class Model(val id: String, val name: String, val file: String, val url: String, val bytes: Long, val gated: Boolean)

    val MODELS = listOf(
        Model("qwen3-0.6b", "Qwen3 0.6B", "Qwen3-0.6B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm", 613_722_264L, gated = false),
        Model("gemma3-270m", "Gemma 3 270M", "gemma3-270m-it-q8.litertlm",
            "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm", 304_005_120L, gated = true),
        Model("gemma3-1b", "Gemma 3 1B", "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm", 584_417_280L, gated = true),
    )

    fun model(id: String): Model? = MODELS.firstOrNull { it.id == id }

    private fun dir(context: Context): File = File(context.filesDir, "litert-models").apply { mkdirs() }
    fun file(context: Context, model: Model): File = File(dir(context), model.file)
    fun downloaded(context: Context, model: Model): Boolean = file(context, model).let { it.exists() && it.length() > 0 }

    /** The model she thinks with when her brain is "litert": the one you activated, if it's here. */
    fun active(context: Context): Model? =
        model(Store(context).configString("litertModel") ?: "")?.takeIf { downloaded(context, it) }

    /** Activate one (or none, with ""). A brain change: where she was is kept first. */
    fun setActive(context: Context, id: String) {
        RestorePoints.create(context)
        closeEngine()
        CapriSettings.set(context, "litertModel", id)
    }

    /** For her page (HostBridge "litert:state") and Su mente: what's here and what's on. */
    fun state(context: Context): JSONObject {
        val on = active(context)
        val list = JSONArray()
        for (m in MODELS) list.put(JSONObject()
            .put("id", m.id).put("name", m.name).put("bytes", m.bytes)
            .put("downloaded", downloaded(context, m)).put("active", on?.id == m.id).put("gated", m.gated))
        return JSONObject().put("ready", on != null).put("model", on?.name ?: JSONObject.NULL).put("models", list)
    }

    /**
     * Download a model into the app's own files. Blocking (a few hundred MB): call it on a
     * background thread. onProgress(percent 0..100). Returns null when it's here, or why not.
     */
    fun download(context: Context, model: Model, onProgress: (Int) -> Unit): String? {
        val part = File(dir(context), model.file + ".part")
        return try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                part.delete()
                return if (model.gated) "gated" else "HTTP $code"
            }
            if (code !in 200..299) return "HTTP $code"
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else model.bytes
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var got = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            if (part.length() <= 0) { part.delete(); return "empty" }
            part.renameTo(file(context, model))
            null
        } catch (e: Exception) {
            part.delete()
            e.message ?: "offline"
        }
    }

    fun delete(context: Context, model: Model) {
        if (active(context)?.id == model.id) setActive(context, "")
        file(context, model).delete()
    }

    // ---------- the engine ----------

    @Volatile private var engine: Engine? = null
    @Volatile private var enginePath: String? = null

    @Synchronized
    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        enginePath = null
    }

    /**
     * One whole answer from the model on this phone. Blocking (loading alone can take
     * seconds): never on the main thread. The messages are her chat's usual ones — the
     * system one becomes the conversation's instruction, the turns before the last become
     * its first messages, and the last one is what's asked.
     */
    @Synchronized
    fun chat(context: Context, messages: JSONArray): String {
        val model = active(context) ?: throw IllegalStateException("nomodel: no downloaded model is on")
        val f = file(context, model)
        if (engine == null || enginePath != f.absolutePath) {
            closeEngine()
            val e = Engine(EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
            e.initialize()
            engine = e
            enginePath = f.absolutePath
        }
        var system = ""
        val turns = mutableListOf<Pair<Boolean, String>>() // (isUser, text)
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val text = m.optString("content")
            if (text.isBlank()) continue
            when (m.optString("role")) {
                "system" -> system = if (system.isBlank()) text else "$system\n$text"
                "assistant" -> turns.add(false to text)
                else -> turns.add(true to text)
            }
        }
        val lastUser = turns.lastOrNull { it.first }?.second ?: turns.lastOrNull()?.second ?: ""
        val before = turns.dropLast(1).takeLast(12)
        val config = ConversationConfig(
            systemInstruction = if (system.isBlank()) null else Contents.of(system),
            initialMessages = before.map { (isUser, text) -> if (isUser) Message.user(text) else Message.model(text) },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
        )
        val conversation = engine!!.createConversation(config)
        try {
            val answer = conversation.sendMessage(lastUser)
            val text = answer.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            return text.ifBlank { answer.toString() }
        } finally {
            runCatching { conversation.close() }
        }
    }
}
