package com.clipsync.app

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.U

/**
 * Samsung DeX-style look for Taskbar (step 1: defaults + button order; step 2: flat dark bar).
 * Called from TaskbarController (patched in by patch_taskbar.py).
 */
object DexStyle {
    private const val DONE = "dex_defaults_v1"
    private const val DONE2 = "dex_defaults_v2"

    /** Runs once: turns on nav buttons, status area and centred icons. */
    @JvmStatic
    fun applyDefaults(ctx: Context) {
        try {
            val p = U.getSharedPreferences(ctx)
            if (!p.getBoolean(DONE2, false)) {
                // DeX look: dark, slightly translucent bar
                p.edit()
                    .putInt("background_tint", 0xD9111114.toInt())
                    .putBoolean(DONE2, true)
                    .apply()
            }
            if (p.getBoolean(DONE, false)) return
            p.edit()
                .putBoolean("button_back", true)
                .putBoolean("button_home", true)
                .putBoolean("button_recents", true)
                .putBoolean("sys_tray", true)
                .putBoolean("full_length", true)
                .putBoolean("centered_icons", true)
                .putBoolean(DONE, true)
                .apply()
        } catch (e: Exception) {
        }
    }

    /** DeX order: Recents, Home, Back. */
    @JvmStatic
    fun arrangeNav(layout: View?) {
        try {
            val nav = layout?.findViewById<LinearLayout>(R.id.navbar_buttons) ?: return
            val views = listOf(R.id.button_recents, R.id.button_home, R.id.button_back)
                .mapNotNull { nav.findViewById<View>(it) }
            if (views.size != 3) return
            views.forEach { nav.removeView(it) }
            views.forEach { nav.addView(it) }
        } catch (e: Exception) {
        }
    }
}
