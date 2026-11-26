package io.github.emmanuelquintana.capri

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings

/**
 * (1.14.0) Her self-check, in plain words and plain text: the things that make her work or
 * fail — drawn over your apps, the microphone, notification access, an unrestricted
 * battery, her service running, the brain she answers with, free space — and the last
 * errors from her internal log (Diag.kt, the same one as always: nothing new is kept).
 * Made to be copied and pasted into a chat (the Report page's «Copiar reporte», and the
 * console's «diagnostico»): no keys, no e-mails, no names of yours in it.
 */
object DiagReport {

    fun build(context: Context): String {
        val app = context.applicationContext
        fun yes(on: Boolean) = context.getString(if (on) R.string.diag_yes else R.string.diag_no)
        val out = StringBuilder()
        out.appendLine(context.getString(R.string.diag_title))
        out.appendLine("Capri ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        out.appendLine("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        out.appendLine(context.getString(R.string.diag_overlay, yes(Settings.canDrawOverlays(app))))
        out.appendLine(context.getString(R.string.diag_mic,
            yes(app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)))
        out.appendLine(context.getString(R.string.diag_notes, yes(CapriNotifications.allowed(app))))
        val pm = app.getSystemService(PowerManager::class.java)
        out.appendLine(context.getString(R.string.diag_battery, yes(pm?.isIgnoringBatteryOptimizations(app.packageName) == true)))
        out.appendLine(context.getString(R.string.diag_service, yes(CapriService.running)))
        val provider = CapriSettings.provider(app)
        val brain = when (provider) {
            "litert" -> context.getString(R.string.ai_litert) + (LiteRtBrain.active(app)?.let { " · ${it.name}" } ?: "")
            "local" -> context.getString(R.string.main_ai_local)
            "pollinations" -> context.getString(R.string.main_ai_free)
            "ollama" -> context.getString(R.string.main_ai_pc) + " · " + CapriSettings.ollamaHost(app)
            else -> CapriSettings.KEYED.firstOrNull { it.id == provider }?.name ?: provider
        }
        out.appendLine(context.getString(R.string.diag_brain, brain))
        val free = StatFs(app.filesDir.path).availableBytes
        out.appendLine(context.getString(R.string.diag_space, when {
            free >= 1_000_000_000 -> "%.1f GB".format(free / 1e9)
            free >= 1_000_000 -> "${free / 1_000_000} MB"
            else -> "${free / 1000} KB"
        }))
        // her last errors, from the one log she has always kept (Diag): the lines marked E
        val errors = Diag.saved().filter { it.contains(" E ") }.takeLast(6)
        out.appendLine(context.getString(R.string.diag_errors))
        if (errors.isEmpty()) out.appendLine(context.getString(R.string.diag_errors_none))
        else errors.forEach { out.appendLine("· $it") }
        return out.toString().trim()
    }
}
