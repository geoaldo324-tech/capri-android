package io.github.emmanuelquintana.capri

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * (1.14.0) The console's bridge to Termux — OFF by default (the «termuxBridge» switch in
 * Ajustes › Teléfono). On, her console gains one command, «termux <comando>», which hands
 * the command to Termux's own RunCommandService to run with its bash, in the background.
 * The console always shows the exact command in a confirmation dialog before anything is
 * sent; nothing runs from here without that yes.
 *
 * What Termux itself asks on its side: it must be installed and running at least once, with
 * allow-external-apps = true in its ~/.termux/termux.properties — if Termux refuses, the
 * command simply never runs there and nothing here can see why (it answers nothing back).
 */
object TermuxBridge {

    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX = "com.termux"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Hand the command to Termux. True when it was sent (not when it worked: Termux says nothing back). */
    fun run(context: Context, command: String): Boolean = runCatching {
        val intent = Intent()
            .setClassName(TERMUX, "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        context.startService(intent)
        true
    }.getOrDefault(false)
}
