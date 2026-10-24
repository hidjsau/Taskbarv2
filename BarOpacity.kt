package com.clipsync.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.farmerbb.taskbar.ui.TaskbarBlurWindow
import com.farmerbb.taskbar.util.U

/**
 * User-adjustable taskbar opacity (0% = fully see-through, 100% = solid).
 *
 * The value is the opacity of the lighter edge of the taskbar's glass gradient; the outer edge
 * is a bit more transparent, like the original look. When nothing was chosen yet, the old
 * built-in values are used (70% normally, 40% when the blur window is active), so nothing
 * changes until the user moves the slider.
 *
 * Read by U.applyGlassBackground; edited from Settings > Appearance > Taskbar opacity.
 */
object BarOpacity {
    const val PREF = "clipsync_bar_opacity"
    private const val OUTER_RATIO = 0.82f

    @JvmStatic
    fun blurActive(ctx: Context): Boolean = try {
        BarTheme.usesBlur(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            TaskbarBlurWindow.isSupported(ctx)
    } catch (t: Throwable) {
        false
    }

    @JvmStatic
    fun defaultPercent(ctx: Context, blurActive: Boolean): Int =
        if (blurActive) 40 else if (BarTheme.usesBlur(ctx)) 70 else 85

    /** Chosen percent, or the default for the current mode when nothing was chosen. */
    @JvmStatic
    fun percent(ctx: Context, blurActive: Boolean): Int {
        val v = U.getSharedPreferences(ctx).getInt(PREF, -1)
        return if (v in 0..100) v else defaultPercent(ctx, blurActive)
    }

    /** Alpha (0-255) for the inner, lighter edge of the gradient. */
    @JvmStatic
    fun innerAlpha(ctx: Context, blurActive: Boolean): Int =
        Math.round(percent(ctx, blurActive) * 255f / 100f).coerceIn(0, 255)

    /** Alpha (0-255) for the outer edge; equal to the inner one when fully solid. */
    @JvmStatic
    fun outerAlpha(ctx: Context, blurActive: Boolean): Int {
        val inner = innerAlpha(ctx, blurActive)
        return if (percent(ctx, blurActive) >= 100) inner
        else Math.round(inner * OUTER_RATIO).coerceIn(0, 255)
    }

    @JvmStatic
    fun summary(ctx: Context): String {
        val v = U.getSharedPreferences(ctx).getInt(PREF, -1)
        return if (v in 0..100) "$v%" else "Default (${defaultPercent(ctx, blurActive(ctx))}%)"
    }

    /** Slider dialog. [onChanged] runs after the value was saved (to refresh the summary). */
    @JvmStatic
    fun showDialog(activity: Activity, onChanged: Runnable?) {
        val blur = blurActive(activity)
        val start = percent(activity, blur)
        val builder = AlertDialog.Builder(activity)
        val c = builder.context
        val d = c.resources.displayMetrics.density

        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * d).toInt()
        box.setPadding(pad, pad / 2, pad, 0)

        val value = TextView(c)
        value.textSize = 28f
        value.gravity = Gravity.CENTER
        value.text = "$start%"

        val seek = SeekBar(c)
        seek.max = 100
        seek.progress = start
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = "$progress%"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val hint = TextView(c)
        hint.textSize = 13f
        hint.alpha = 0.7f
        hint.setPadding(0, (8 * d).toInt(), 0, 0)
        val extra = if (blur) " Blur is active, so lower values show more of the blurred background." else ""
        hint.text = "0% is fully see-through, 100% is solid.$extra"

        box.addView(value)
        box.addView(seek)
        box.addView(hint)

        builder.setTitle("Taskbar opacity")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                U.getSharedPreferences(activity).edit().putInt(PREF, seek.progress).apply()
                U.restartTaskbar(activity)
                onChanged?.run()
            }
            .setNeutralButton("Default") { _, _ ->
                U.getSharedPreferences(activity).edit().remove(PREF).apply()
                U.restartTaskbar(activity)
                onChanged?.run()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
