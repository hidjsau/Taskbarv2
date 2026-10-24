package com.clipsync.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.U
import rikka.shizuku.Shizuku

/**
 * EXPERIMENTAL: reserve a strip at the bottom of the screen for the taskbar (like Windows), so
 * apps are laid out above it instead of running underneath it.
 *
 * How: Android reserves the bottom strip for the system navigation bar, and apps respect it.
 * With Shizuku (shell access) we can override the framework's navigation-bar height with a
 * runtime resource overlay (Android 12+, `cmd overlay fabricate`) and set it to the taskbar's
 * height. The taskbar window then sits in the strip that apps leave free.
 *
 * Limits: bottom taskbar only; Android 12+; the ROM may ignore the override; full-screen
 * (immersive) apps and games still cover the whole screen; the strip stays empty while the
 * taskbar is hidden. "Remove" undoes everything.
 */
object BarSpace {
    private const val NAME_HEIGHT = "clipsync_taskbar_space_h"
    private const val NAME_FRAME = "clipsync_taskbar_space_f"
    private const val PREF_ON = "clipsync_space_on"

    private val ui = Handler(Looper.getMainLooper())

    @JvmStatic
    fun summary(ctx: Context): String =
        if (U.getSharedPreferences(ctx).getBoolean(PREF_ON, false)) "On (experimental)"
        else "Off (experimental, needs Shizuku)"

    /** Taskbar thickness in dp (the icon size, which is what the bar is as tall as). */
    private fun barDp(ctx: Context): Int {
        val d = ctx.resources.displayMetrics.density
        return Math.round(ctx.resources.getDimension(R.dimen.tb_icon_size) / d).coerceIn(24, 120)
    }

    private fun cleanup(p: Process?) {
        if (p == null) return
        try { p.outputStream.close() } catch (t: Throwable) {}
        try { p.inputStream.close() } catch (t: Throwable) {}
        try { p.errorStream.close() } catch (t: Throwable) {}
        try { p.destroy() } catch (t: Throwable) {}
    }

    /** Runs a shell script as shell uid through Shizuku with a time limit. Output or null. */
    private fun sh(script: String): String? {
        val result = arrayOfNulls<String>(1)
        val holder = arrayOfNulls<Process>(1)
        val worker = Thread {
            try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("sh", "-c", "$script 2>&1"), null, null) as Process
                holder[0] = p
                result[0] = p.inputStream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                result[0] = null
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(10_000L)
        val timedOut = worker.isAlive
        cleanup(holder[0])
        return if (timedOut) null else result[0]
    }

    private fun toast(ctx: Context, msg: String) {
        ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dimenCmd(name: String, res: String, dp: Int): String {
        // dimension resource: type 0x05, data = value (23.0 fixed point) in dp units
        val data = "0x" + java.lang.Long.toHexString((dp.toLong() shl 8) or 1L)
        return "cmd overlay fabricate --target android --name $name android:dimen/$res 0x5 $data && " +
            "cmd overlay enable com.android.shell:$name"
    }

    private fun reserve(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast(ctx, "Needs Android 12 or newer")
            return
        }
        if (TaskSource.shizukuState() != 2) {
            toast(ctx, "Start Shizuku and allow Taskbar first")
            return
        }
        val dp = barDp(ctx)
        Thread {
            val out1 = sh(dimenCmd(NAME_HEIGHT, "navigation_bar_height", dp))
            val out2 = sh(dimenCmd(NAME_FRAME, "navigation_bar_frame_height", dp))
            val list = sh("cmd overlay list android") ?: ""
            val ok = list.lines().any { it.contains("[x]") && it.contains(NAME_HEIGHT) }
            if (ok) {
                U.getSharedPreferences(ctx).edit().putBoolean(PREF_ON, true).apply()
                toast(ctx, "Reserved $dp dp at the bottom. Reopen apps if they didn't move.")
            } else {
                val why = ((out1 ?: out2) ?: "no answer from Shizuku").trim().take(120)
                toast(ctx, "The system did not accept it: $why")
            }
        }.start()
    }

    private fun remove(ctx: Context) {
        if (TaskSource.shizukuState() != 2) {
            toast(ctx, "Start Shizuku and allow Taskbar first")
            return
        }
        Thread {
            sh("cmd overlay disable com.android.shell:$NAME_HEIGHT; cmd overlay disable com.android.shell:$NAME_FRAME")
            U.getSharedPreferences(ctx).edit().putBoolean(PREF_ON, false).apply()
            toast(ctx, "Screen space override removed")
        }.start()
    }

    @JvmStatic
    fun showDialog(activity: Activity, onChanged: Runnable?) {
        val msg = "Apps will open above the taskbar instead of underneath it (bottom taskbar only, " +
            "Android 12+). Uses Shizuku to change the system navigation-bar height to " +
            "${barDp(activity)} dp.\n\n" +
            "Experimental: some ROMs ignore it, and full-screen apps and games still cover the whole " +
            "screen. If anything looks wrong, tap Remove."
        AlertDialog.Builder(activity)
            .setTitle("Reserve screen space")
            .setMessage(msg)
            .setPositiveButton("Reserve") { _, _ ->
                reserve(activity)
                ui.postDelayed({ onChanged?.run() }, 2500L)
            }
            .setNeutralButton("Remove") { _, _ ->
                remove(activity)
                ui.postDelayed({ onChanged?.run() }, 1500L)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
