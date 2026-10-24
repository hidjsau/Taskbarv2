package com.clipsync.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.farmerbb.taskbar.util.TaskbarPosition
import com.farmerbb.taskbar.util.U

/**
 * Ready-made looks for the taskbar that are drawn by the app itself, so they look the same on
 * every ROM (HyperOS and other custom ROMs included) and do not need the blur window.
 *
 * "Glass (blur)" is the original look and keeps using the blur window where the system allows it.
 * Every other theme switches the blur window off and paints a dark gradient, a soft edge highlight
 * and rounded corners on the screen-facing side. The Taskbar opacity slider (BarOpacity) scales them.
 *
 * Used by U.applyGlassBackground; chosen in Settings > Appearance > Taskbar theme.
 */
object BarTheme {
    const val PREF = "clipsync_bar_theme"
    const val GLASS = "glass"
    private const val CORNER_DP = 14
    private const val OUTER_RATIO = 0.82f

    private class Def(
        val id: String,
        val name: String,
        val inner: Int,          // colour on the screen-facing side
        val outer: Int,          // colour on the screen-edge side
        val stops: IntArray? = null, // optional colours along the bar (instead of inner/outer)
        val rounded: Boolean = true,
        val highlight: Boolean = true
    )

    private fun c(v: Long) = v.toInt()

    private val defs = listOf(
        Def(GLASS, "Glass (blur)", c(0xFF7B88A6), c(0xFF414C66), rounded = false, highlight = false),
        Def("midnight", "Midnight", c(0xFF2B3A55), c(0xFF0F1626)),
        Def("aurora", "Aurora", 0, 0,
            stops = intArrayOf(c(0xFF4B2A8F), c(0xFF1E4F9C), c(0xFF0F8B8D))),
        Def("rose", "Rose", c(0xFF5A2A44), c(0xFF1E1020)),
        Def("ocean", "Ocean", c(0xFF0F5C6B), c(0xFF07222B)),
        Def("accent", "Accent", 0, 0),   // built from the accent colour
        Def("amoled", "AMOLED black", c(0xFF000000), c(0xFF000000), rounded = false, highlight = false)
    )

    private fun find(id: String): Def = defs.firstOrNull { it.id == id } ?: defs[0]

    @JvmStatic
    fun themeId(ctx: Context): String {
        val id = U.getSharedPreferences(ctx).getString(PREF, GLASS) ?: GLASS
        return find(id).id
    }

    /** True when the blur window should be used (only the original Glass look uses it). */
    @JvmStatic
    fun usesBlur(ctx: Context): Boolean = themeId(ctx) == GLASS

    @JvmStatic
    fun summary(ctx: Context): String = find(themeId(ctx)).name

    private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Dark gradient colours built from the accent colour (blue when the accent is grey/white). */
    private fun accentColors(ctx: Context): Pair<Int, Int> {
        val hsv = FloatArray(3)
        Color.colorToHSV(U.getAccentColor(ctx) or 0xFF000000.toInt(), hsv)
        if (hsv[1] < 0.15f) {
            hsv[0] = 215f
            hsv[1] = 0.55f
        } else {
            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        }
        hsv[2] = 0.46f
        val inner = Color.HSVToColor(hsv)
        hsv[2] = 0.14f
        val outer = Color.HSVToColor(hsv)
        return Pair(inner, outer)
    }

    /** 0 = bottom bar, 1 = top bar, 2 = bar on the left, 3 = bar on the right. */
    private fun positionOf(ctx: Context): Int = when {
        TaskbarPosition.isVerticalLeft(ctx) -> 2
        TaskbarPosition.isVerticalRight(ctx) -> 3
        TaskbarPosition.isBottom(ctx) -> 0
        else -> 1
    }

    private fun orientation(pos: Int): GradientDrawable.Orientation = when (pos) {
        0 -> GradientDrawable.Orientation.TOP_BOTTOM
        1 -> GradientDrawable.Orientation.BOTTOM_TOP
        2 -> GradientDrawable.Orientation.RIGHT_LEFT
        else -> GradientDrawable.Orientation.LEFT_RIGHT
    }

    /** Corner radii: only the corners that face into the screen are rounded. */
    private fun radii(pos: Int, r: Float): FloatArray = when (pos) {
        0 -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        1 -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        2 -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        else -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
    }

    private fun build(ctx: Context, def: Def, pct: Int, pos: Int): Drawable {
        val d = ctx.resources.displayMetrics.density
        val a = Math.round(pct * 255f / 100f).coerceIn(0, 255)
        val oa = if (pct >= 100) a else Math.round(a * OUTER_RATIO).coerceIn(0, 255)
        val r = CORNER_DP * d

        val colors = if (def.id == "accent") accentColors(ctx) else Pair(def.inner, def.outer)

        val body: GradientDrawable = if (def.stops != null) {
            val o = if (pos <= 1) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM
            GradientDrawable(o, IntArray(def.stops.size) { withAlpha(def.stops[it], a) })
        } else {
            GradientDrawable(orientation(pos), intArrayOf(withAlpha(colors.first, a), withAlpha(colors.second, oa)))
        }
        if (def.rounded) body.cornerRadii = radii(pos, r)

        val layers = ArrayList<Drawable>()
        layers.add(body)

        // multi-colour themes get a dark shade towards the screen edge so they stay calm
        if (def.stops != null) {
            val shade = GradientDrawable(
                orientation(pos),
                intArrayOf(0x00000000, withAlpha(Color.BLACK, Math.round(a * 0.45f)))
            )
            if (def.rounded) shade.cornerRadii = radii(pos, r)
            layers.add(shade)
        }

        // thin soft highlight on the screen-facing edge
        var strip = -1
        if (def.highlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val s = GradientDrawable()
            s.setColor(withAlpha(Color.WHITE, Math.round(0x44 * pct / 100f)))
            layers.add(s)
            strip = layers.size - 1
        }

        val ld = LayerDrawable(layers.toTypedArray())
        if (strip >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val t = Math.max(1, d.toInt())
            val inset = if (def.rounded) r.toInt() else 0
            when (pos) {
                0 -> {
                    ld.setLayerGravity(strip, Gravity.TOP)
                    ld.setLayerHeight(strip, t)
                    ld.setLayerInset(strip, inset, 0, inset, 0)
                }
                1 -> {
                    ld.setLayerGravity(strip, Gravity.BOTTOM)
                    ld.setLayerHeight(strip, t)
                    ld.setLayerInset(strip, inset, 0, inset, 0)
                }
                2 -> {
                    ld.setLayerGravity(strip, Gravity.RIGHT)
                    ld.setLayerWidth(strip, t)
                    ld.setLayerInset(strip, 0, inset, 0, inset)
                }
                else -> {
                    ld.setLayerGravity(strip, Gravity.LEFT)
                    ld.setLayerWidth(strip, t)
                    ld.setLayerInset(strip, 0, inset, 0, inset)
                }
            }
        }
        return ld
    }

    /**
     * Paints the selected theme on the taskbar. Returns false when the original Glass look is
     * selected (the caller then draws it as before).
     */
    @JvmStatic
    fun apply(ctx: Context, view: View, tint: Int): Boolean {
        val id = themeId(ctx)
        if (id == GLASS) return false
        return try {
            view.background = build(ctx, find(id), BarOpacity.percent(ctx, false), positionOf(ctx))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Theme picker with a small preview of every look. [onChanged] runs after a new theme was saved. */
    @JvmStatic
    fun showDialog(activity: Activity, onChanged: Runnable?) {
        val current = themeId(activity)
        val builder = AlertDialog.Builder(activity)
        val ctx = builder.context
        val d = ctx.resources.displayMetrics.density

        val adapter = object : ArrayAdapter<Def>(ctx, 0, defs) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val def = getItem(position)!!
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val pad = (16 * d).toInt()
                row.setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())

                val sw = View(ctx)
                sw.layoutParams = LinearLayout.LayoutParams((72 * d).toInt(), (34 * d).toInt())
                sw.background = build(ctx, def, 100, 0)

                val tv = TextView(ctx)
                tv.textSize = 16f
                tv.setPadding(pad, 0, 0, 0)
                tv.text = if (def.id == current) def.name + "  \u2713" else def.name

                row.addView(sw)
                row.addView(tv)
                return row
            }
        }

        builder.setTitle("Taskbar theme")
            .setAdapter(adapter) { _, which ->
                val chosen = defs[which]
                U.getSharedPreferences(activity).edit().putString(PREF, chosen.id).apply()
                U.restartTaskbar(activity)
                onChanged?.run()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
