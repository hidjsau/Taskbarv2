package com.clipsync.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.farmerbb.taskbar.R
import java.util.WeakHashMap

/**
 * Focus feedback for Taskbar icons, nothing else:
 *  - the icon of the app in front is shown larger, every other icon at normal size
 *  - the change is a quick scale (about 0.1 s), played the moment an icon is tapped
 *  - no running bar, no highlight, no sliding pill
 * Called from TaskbarController (updateRunningAppIndicators and the icon click).
 */
object RunningStyle {
    const val PREF_ANIM = "clipsync_switch_anim" // no longer used, kept so old code still compiles

    private const val FOCUS_SCALE = 1.2f   // size of the focused icon (1.0 = normal)
    private const val DURATION_MS = 100L   // scale animation time
    private const val FORCE_MS = 2500L     // how long a tapped app counts as focused before the system confirms

    // Set when an icon is tapped, so the enlarge does not wait for the next focus check.
    private var forcedPkg: String? = null
    private var forcedFrom: String? = null
    private var forcedAt = 0L

    // last size each icon view was told to be
    private val targets = WeakHashMap<View, Float>()

    /** Package in front right now. A just-tapped app counts as focused until the system catches up. */
    @JvmStatic
    fun focusedPackage(ctx: Context): String? {
        val f = TaskSource.focused(ctx)
        val forced = forcedPkg ?: return f
        val expired = SystemClock.elapsedRealtime() - forcedAt > FORCE_MS
        // confirmed, timed out, or the system reports some other app: trust the system again
        if (f == forced || expired || (f != null && f != forcedFrom)) {
            forcedPkg = null
            return f
        }
        return forced
    }

    /** Call when an icon is tapped: enlarge it and shrink the others right away. */
    @JvmStatic
    fun onLaunched(ctx: Context, entry: View, packageName: String) {
        try {
            forcedFrom = try { TaskSource.focused(ctx) } catch (e: Exception) { null }
            forcedPkg = packageName
            forcedAt = SystemClock.elapsedRealtime()
            val parent = entry.parent as? ViewGroup ?: return
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val icon = child.findViewById<View>(R.id.icon) ?: continue
                setScale(icon, if (child === entry) FOCUS_SCALE else 1f, true)
            }
        } catch (e: Exception) {
        }
    }

    private fun setScale(icon: View, target: Float, animateNew: Boolean) {
        val known = targets[icon]
        if (known == target) return
        targets[icon] = target
        icon.animate().cancel()
        if (known == null && !animateNew) {
            icon.scaleX = target
            icon.scaleY = target
        } else {
            icon.animate().scaleX(target).scaleY(target)
                .setDuration(DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    @JvmStatic
    fun apply(ctx: Context, entry: View, packageName: String, running: Boolean, focusedPkg: String?) {
        try {
            val d = ctx.resources.displayMetrics.density
            val focused = packageName == focusedPkg

            // no running bar and no highlight behind the icon
            entry.findViewById<ImageView>(R.id.running_app_indicator)?.visibility = View.GONE
            val icon = entry.findViewById<ImageView>(R.id.icon)
            if (icon != null) {
                icon.background = null
                setScale(icon, if (focused) FOCUS_SCALE else 1f, false)
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
