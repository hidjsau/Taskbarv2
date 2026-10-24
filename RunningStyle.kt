package com.clipsync.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.U
import java.lang.ref.WeakReference

/**
 * Windows-style running indicator for Taskbar icons.
 *  - running app:  short rounded bar under the icon
 *  - focused app:  longer, brighter bar + soft highlight behind the icon
 *  - hover/press:  soft state layer over the icon
 * Called from TaskbarController.updateRunningAppIndicators.
 */
object RunningStyle {
    const val PREF_ANIM = "clipsync_switch_anim"

    private var lastFocused: String? = null
    private var switchedFrom: String? = null
    private var switchedTo: String? = null
    private val recentViews = HashMap<String, WeakReference<View>>()

    /**
     * Package that currently has focus, or null if unknown. Also notes when the focus moved to
     * another app, so apply() can play the switch animation once (main thread only).
     */
    @JvmStatic
    fun focusedPackage(ctx: Context): String? {
        val f = TaskSource.focused(ctx)
        if (f != lastFocused) {
            switchedFrom = lastFocused
            switchedTo = if (f != null && lastFocused != null) f else null
            lastFocused = f
        } else {
            switchedTo = null
        }
        return f
    }

    @JvmStatic
    fun apply(ctx: Context, entry: View, packageName: String, running: Boolean, focusedPkg: String?) {
        try {
            val d = ctx.resources.displayMetrics.density
            val accent = U.getAccentColor(ctx)
            val focused = running && packageName == focusedPkg
            recentViews[packageName] = WeakReference(entry)
            val animate = focused && packageName == switchedTo &&
                U.getSharedPreferences(ctx).getBoolean(PREF_ANIM, true)
            if (animate) switchedTo = null
            var focusBg: GradientDrawable? = null

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
                    if (animate) bg.alpha = 0   // revealed by the animation below
                    focusBg = bg
                    icon.background = InsetDrawable(bg, (4 * d).toInt())
                } else {
                    icon.background = null
                }
            }

            if (animate) animateSwitch(entry, icon, indicator, focusBg, switchedFrom, accent, d)

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

    // ---- app switch animation ---------------------------------------------------------

    private fun relRect(parent: View, v: View, inset: Int): Rect {
        val p = IntArray(2)
        val l = IntArray(2)
        parent.getLocationOnScreen(p)
        v.getLocationOnScreen(l)
        val x = l[0] - p[0]
        val y = l[1] - p[1]
        return Rect(x + inset, y + inset, x + v.width - inset, y + v.height - inset)
    }

    private fun pop(icon: View) {
        icon.animate().cancel()
        icon.scaleX = 0.86f
        icon.scaleY = 0.86f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /**
     * Quick, minimal feedback when the focused app changes: the highlight slides from the old
     * app's icon to the new one, the new icon pops and its running bar grows. When the old icon
     * is not on screen (list was rebuilt), the highlight just fades in with the pop.
     */
    private fun animateSwitch(
        entry: View, icon: ImageView?, indicator: ImageView?, bg: GradientDrawable?,
        from: String?, accent: Int, d: Float
    ) {
        if (icon == null || bg == null) return

        if (indicator != null) {
            indicator.animate().cancel()
            indicator.scaleX = 0.4f
            indicator.animate().scaleX(1f).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        val parent = entry.parent as? ViewGroup
        val prev = if (from != null) recentViews[from]?.get() else null
        val prevIcon = prev?.findViewById<View>(R.id.icon)
        val canSlide = parent != null && prev != null && prevIcon != null &&
            prev.parent === parent && entry.isLaidOut && entry.width > 0 &&
            prev.isLaidOut && prev.width > 0

        if (!canSlide) {
            val fade = ValueAnimator.ofInt(0, 255)
            fade.duration = 200
            fade.addUpdateListener { bg.alpha = it.animatedValue as Int }
            fade.start()
            pop(icon)
            return
        }

        val inset = (4 * d).toInt()
        val a = relRect(parent!!, prevIcon!!, inset)
        val b = relRect(parent, icon, inset)

        val pill = GradientDrawable()
        pill.cornerRadius = 10f * d
        pill.setColor((accent and 0x00FFFFFF) or (0x4D shl 24))
        pill.setBounds(a.left, a.top, a.right, a.bottom)
        parent.overlay.add(pill)

        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 240
        va.interpolator = DecelerateInterpolator(1.6f)
        va.addUpdateListener {
            val f = it.animatedValue as Float
            pill.setBounds(
                a.left + ((b.left - a.left) * f).toInt(),
                a.top + ((b.top - a.top) * f).toInt(),
                a.right + ((b.right - a.right) * f).toInt(),
                a.bottom + ((b.bottom - a.bottom) * f).toInt()
            )
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                parent.overlay.remove(pill)
                bg.alpha = 255
                pop(icon)
            }
        })
        va.start()
    }
}
