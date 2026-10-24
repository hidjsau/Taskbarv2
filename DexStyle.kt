package com.clipsync.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.TaskbarPosition
import com.farmerbb.taskbar.util.U

/**
 * Samsung DeX-style look for Taskbar.
 * Step 1: defaults + button order. Step 2: flat dark bar.
 * Step 3: sleek icons, thin divider after the apps button.
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

    /** Space used by our thin divider after the apps button (px). Used for centring maths. */
    @JvmStatic
    fun extraStartPx(ctx: Context): Float {
        if (TaskbarPosition.isVertical(ctx)) return 0f
        return 9f * ctx.resources.displayMetrics.density
    }

    /** DeX order (Recents, Home, Back), new icons, and the divider. */
    @JvmStatic
    fun arrangeNav(layout: View?) {
        if (layout == null) return
        try {
            val nav = layout.findViewById<LinearLayout>(R.id.navbar_buttons)
            if (nav != null) {
                val icons = listOf(
                    R.id.button_recents to R.drawable.dex_recents,
                    R.id.button_home to R.drawable.dex_home,
                    R.id.button_back to R.drawable.dex_back
                )
                val views = icons.mapNotNull { nav.findViewById<ImageView>(it.first) }
                if (views.size == 3) {
                    icons.forEachIndexed { i, p -> views[i].setImageResource(p.second) }
                    views.forEach { nav.removeView(it) }
                    views.forEach { nav.addView(it) }
                }
            }
        } catch (e: Exception) {
        }
        try {
            addDivider(layout)
        } catch (e: Exception) {
        }
    }

    const val DIVIDER_TAG = "clipsync_divider"

    private fun addDivider(layout: View) {
        val ctx = layout.context
        val start = layout.findViewById<View>(R.id.start_button) ?: return
        val parent = start.parent as? LinearLayout ?: return
        if (parent.orientation != LinearLayout.HORIZONTAL) return
        val d = ctx.resources.displayMetrics.density
        val line = View(ctx)
        val shape = GradientDrawable()
        shape.cornerRadius = d
        shape.setColor((U.getAccentColor(ctx) and 0x00FFFFFF) or (0x40 shl 24))
        line.background = shape
        line.tag = DIVIDER_TAG
        val lp = LinearLayout.LayoutParams(Math.max(1, Math.round(d)), (22 * d).toInt())
        lp.gravity = Gravity.CENTER_VERTICAL
        lp.marginStart = (4 * d).toInt()
        lp.marginEnd = (4 * d).toInt()
        parent.addView(line, parent.indexOfChild(start) + 1, lp)
    }
}
