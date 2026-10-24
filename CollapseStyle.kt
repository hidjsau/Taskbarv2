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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.farmerbb.taskbar.R
import com.farmerbb.taskbar.util.TaskbarPosition
import com.farmerbb.taskbar.util.U
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
            // flat bar background only while expanded (no grey box behind the handle)
            layout.setBackgroundColor(if (collapsed) 0 else U.getBackgroundTint(ctx))
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
        if (collapsed) {
            // Premium collapsed pill: flat against the screen edge, rounded on the inside,
            // dark fill + light outline so it reads on any background. No chevron.
            val edge = tabEdge(button, s)
            button.background = Handle(
                0, accent, dir, d, edge,
                ContextCompat.getColor(ctx, R.color.tb_pill_fill),
                ContextCompat.getColor(ctx, R.color.tb_pill_outline)
            )
            button.contentDescription = ctx.getString(R.string.tb_pill_show_taskbar)
            animatePill(button)
        } else {
            button.background = Handle(0, accent, dir, d)
            button.contentDescription = ctx.getString(R.string.tb_pill_hide_taskbar)
            button.setOnTouchListener(null)
            button.setOnHoverListener(null)
            button.animate().cancel()
            button.alpha = 1f
            button.scaleX = 1f
            button.scaleY = 1f
        }
    }

    private const val PILL_IDLE_ALPHA = 0.72f

    /** Fade in with M3 emphasized easing; full opacity on touch/hover, small press scale. */
    private fun animatePill(button: View) {
        button.animate().cancel()
        button.alpha = 0f
        button.animate()
            .alpha(PILL_IDLE_ALPHA)
            .setDuration(250)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()

        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().alpha(1f).scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().alpha(PILL_IDLE_ALPHA).scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
        button.setOnHoverListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> v.animate().alpha(1f).setDuration(100).start()
                MotionEvent.ACTION_HOVER_EXIT -> v.animate().alpha(PILL_IDLE_ALPHA).setDuration(150).start()
            }
            false
        }
    }

    private const val E_LEFT = 0
    private const val E_TOP = 1
    private const val E_RIGHT = 2
    private const val E_BOTTOM = 3

    /**
     * Which local edge of the button faces the screen edge the pill hugs.
     * Accounts for the mirrored (rotationY 180) and rotated (+-90) buttons in the layouts.
     */
    private fun tabEdge(button: View, side: Int): Int {
        var vx = 0f
        var vy = 0f
        when (side) {
            LEFT -> vx = -1f
            RIGHT -> vx = 1f
            else -> vy = 1f
        }
        if (abs(button.rotationY) == 180f) vx = -vx
        val rot = button.rotation
        if (rot != 0f) {
            val a = Math.toRadians((-rot).toDouble())
            val nx = (vx * cos(a) - vy * sin(a)).toFloat()
            val ny = (vx * sin(a) + vy * cos(a)).toFloat()
            vx = nx
            vy = ny
        }
        return if (abs(vx) >= abs(vy)) (if (vx < 0) E_LEFT else E_RIGHT)
        else (if (vy < 0) E_TOP else E_BOTTOM)
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
    private class Handle(
        val pill: Int,
        val color: Int,
        val dir: Int,
        val d: Float,
        val edge: Int = -1,
        val tabFill: Int = 0,
        val tabOutline: Int = 0
    ) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
        }
        private val tabPath = Path()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * d
        }
        private val path = Path()

        private fun drawTab(c: Canvas) {
            val b = bounds
            val t = 14f * d      // visible thickness
            val inset = 4f * d   // along the edge
            val o = 2f * d       // pushes the flat-side stroke past the screen edge
            val r = t / 2f
            val rect = when (edge) {
                E_LEFT -> RectF(b.left - o, b.top + inset, b.left + t, b.bottom - inset)
                E_RIGHT -> RectF(b.right - t, b.top + inset, b.right + o, b.bottom - inset)
                E_TOP -> RectF(b.left + inset, b.top - o, b.right - inset, b.top + t)
                else -> RectF(b.left + inset, b.bottom - t, b.right - inset, b.bottom + o)
            }
            // TL, TR, BR, BL (x, y each): flat on the edge side, rounded on the inner side
            val radii = when (edge) {
                E_LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                E_RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                E_TOP -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                else -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            tabPath.reset()
            tabPath.addRoundRect(rect, radii, Path.Direction.CW)
            fill.color = tabFill
            c.drawPath(tabPath, fill)
            outline.color = tabOutline
            c.drawPath(tabPath, outline)
        }

        override fun draw(c: Canvas) {
            if (edge >= 0) {
                drawTab(c)
                return
            }
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
