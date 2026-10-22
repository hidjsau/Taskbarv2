package com.clipsync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.farmerbb.taskbar.util.TaskbarPosition
import com.farmerbb.taskbar.util.U
import java.util.WeakHashMap

/**
 * Sleek hide/show chevron for Taskbar, plus a choice of where the collapsed
 * taskbar handle sits: same as the taskbar (default), left, right or bottom centre.
 * Called from TaskbarController.updateButton (patched in by patch_taskbar.py).
 */
object CollapseStyle {
    const val PREF = "clipsync_collapse_pos"

    private val state = WeakHashMap<View, Boolean>()
    private val sizes = WeakHashMap<View, IntArray>()

    private const val LEFT = -1
    private const val CENTER = 0
    private const val RIGHT = 1

    private fun ownSide(ctx: Context): Int =
        if (TaskbarPosition.getTaskbarPosition(ctx).contains("right")) RIGHT else LEFT

    private fun side(ctx: Context, collapsed: Boolean): Int {
        val own = ownSide(ctx)
        if (!collapsed) return own
        return when (U.getSharedPreferences(ctx).getString(PREF, "default")) {
            "left" -> LEFT
            "right" -> RIGHT
            "bottom_center" -> CENTER
            else -> own
        }
    }

    @JvmStatic
    fun update(ctx: Context, layout: View?, button: View?, collapsed: Boolean) {
        if (layout == null || button == null) return
        try {
            state[layout] = collapsed
            style(ctx, button, collapsed)
            layout.postDelayed({ place(ctx, layout) }, 80)
        } catch (e: Exception) {
        }
    }

    private fun style(ctx: Context, button: View, collapsed: Boolean) {
        val d = ctx.resources.displayMetrics.density
        val s = side(ctx, collapsed)
        val accent = U.getAccentColor(ctx)
        val lp = button.layoutParams
        if (lp != null) {
            val saved = sizes.getOrPut(button) { intArrayOf(lp.width, lp.height) }
            if (collapsed) {
                val wide = s == CENTER
                lp.width = ((if (wide) 56 else 24) * d).toInt()
                lp.height = ((if (wide) 24 else 56) * d).toInt()
            } else {
                lp.width = saved[0]
                lp.height = saved[1]
            }
            button.layoutParams = lp
        }
        (button as? TextView)?.text = ""
        button.setPadding(0, 0, 0, 0)
        val dir = if (collapsed) {
            when (s) {
                LEFT -> 0
                RIGHT -> 180
                else -> 270
            }
        } else {
            if (s == RIGHT) 0 else 180
        }
        val pill = if (collapsed) ((U.getBackgroundTint(ctx) and 0x00FFFFFF) or (0xE0 shl 24)) else 0
        button.background = Handle(pill, accent, dir, d)
    }

    private fun place(ctx: Context, layout: View) {
        val lp = layout.layoutParams as? WindowManager.LayoutParams ?: return
        val collapsed = state[layout] ?: false
        val vert = if (TaskbarPosition.getTaskbarPosition(ctx).contains("top")) Gravity.TOP else Gravity.BOTTOM
        val g = when (side(ctx, collapsed)) {
            CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            RIGHT -> vert or Gravity.RIGHT
            else -> vert or Gravity.LEFT
        }
        if (lp.gravity != g) {
            lp.gravity = g
            try {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(layout, lp)
            } catch (e: Exception) {
            }
        }
    }

    /** Rounded handle with a thin rounded chevron. dir: 0 = points right, 180 = left, 270 = up. */
    private class Handle(val pill: Int, val color: Int, val dir: Int, val d: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * d
        }
        private val path = Path()

        override fun draw(c: Canvas) {
            val b = bounds
            if (pill != 0) {
                fill.color = pill
                val r = minOf(b.width(), b.height()) / 2f
                c.drawRoundRect(RectF(b), r, r, fill)
            }
            line.color = color
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val a = 5.5f * d
            path.reset()
            path.moveTo(-0.55f * a, -a)
            path.lineTo(0.55f * a, 0f)
            path.lineTo(-0.55f * a, a)
            c.save()
            c.translate(cx, cy)
            c.rotate(dir.toFloat())
            c.drawPath(path, line)
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
