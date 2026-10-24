package com.clipsync.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.U

/**
 * Windows-style running indicator for Taskbar icons.
 *  - running app:  short rounded bar under the icon
 *  - focused app:  longer, brighter bar + soft highlight behind the icon
 *  - hover/press:  soft state layer over the icon
 * Called from TaskbarController.updateRunningAppIndicators.
 */
object RunningStyle {
    /** Package that currently has focus, or null if unknown. */
    @JvmStatic
    fun focusedPackage(ctx: Context): String? = TaskSource.focused(ctx)

    @JvmStatic
    fun apply(ctx: Context, entry: View, packageName: String, running: Boolean, focusedPkg: String?) {
        try {
            val d = ctx.resources.displayMetrics.density
            val accent = U.getAccentColor(ctx)
            val focused = running && packageName == focusedPkg

            val indicator = entry.findViewById<ImageView>(R.id.running_app_indicator)
            val icon = entry.findViewById<ImageView>(R.id.icon)

            if (indicator != null) {
                if (!running) {
                    indicator.visibility = View.GONE
                } else {
                    val bar = GradientDrawable()
                    bar.shape = GradientDrawable.RECTANGLE
                    bar.cornerRadius = 2f * d
                    bar.setSize(((if (focused) 18 else 8) * d).toInt(), (3 * d).toInt())
                    bar.setColor(if (focused) accent else ((accent and 0x00FFFFFF) or (0x99 shl 24)))
                    indicator.clearColorFilter()
                    indicator.setImageDrawable(bar)
                    indicator.visibility = View.VISIBLE
                }
            }

            // soft highlight behind the focused app's icon
            if (icon != null) {
                if (focused) {
                    val bg = GradientDrawable()
                    bg.cornerRadius = 10f * d
                    bg.setColor((accent and 0x00FFFFFF) or (0x2E shl 24))
                    icon.background = InsetDrawable(bg, (4 * d).toInt())
                } else {
                    icon.background = null
                }
            }

            // hover (8%) and pressed (12%) state layers, set once per view
            if (entry is FrameLayout && entry.getTag(R.id.icon) == null) {
                entry.setTag(R.id.icon, true)
                fun layer(alpha: Int): InsetDrawable {
                    val g = GradientDrawable()
                    g.cornerRadius = 10f * d
                    g.setColor((0xFFFFFF) or (alpha shl 24))
                    return InsetDrawable(g, (4 * d).toInt())
                }
                val states = StateListDrawable()
                states.addState(intArrayOf(android.R.attr.state_pressed), layer(0x1F))
                states.addState(intArrayOf(android.R.attr.state_hovered), layer(0x14))
                states.addState(intArrayOf(), layer(0x00))
                entry.foreground = states
            }
        } catch (e: Exception) {
        }
    }
}
