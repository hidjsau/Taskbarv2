package com.clipsync.app

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.farmerbb.taskbar.R
import java.lang.ref.WeakReference

/**
 * Adds a "Sync" button on the right side of the Taskbar, just before the status area.
 * Called from TaskbarController (patched in by patch_taskbar.py).
 * Everything here is optional: if the toggle is off, nothing is added.
 */
object TaskbarSyncButton {
    private var ref: WeakReference<View>? = null

    @JvmStatic
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("c", 0).getBoolean("tbbutton", true)

    /** Width the button needs (px), used for the taskbar size maths. */
    @JvmStatic
    fun sizePx(ctx: Context): Float = 44f * ctx.resources.displayMetrics.density

    @JvmStatic
    fun attach(ctx: Context, anchor: View, accent: Int) {
        try {
            val old = ref?.get()
            if (old != null) (old.parent as? LinearLayout)?.removeView(old)
            ref = null
            if (!enabled(ctx)) return
            val parent = anchor.parent as? LinearLayout ?: return

            val iv = ImageView(ctx)
            iv.setImageResource(R.drawable.ic_sync)
            iv.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            val pad = (10 * ctx.resources.displayMetrics.density).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.contentDescription = "Send clipboard"
            iv.setOnClickListener {
                try {
                    ctx.startActivity(
                        Intent(ctx, SendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }

            iv.setOnLongClickListener {
                try {
                    ctx.startActivity(
                        Intent(ctx, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
                true
            }

            val d = ctx.resources.displayMetrics.density
            val side = (40 * d).toInt()
            val icon = ctx.resources.getDimensionPixelSize(R.dimen.tb_icon_size)
            val lp: LinearLayout.LayoutParams
            val tray = parent.findViewById<View>(R.id.add_systray_here)
            val idx: Int
            if (parent.orientation == LinearLayout.VERTICAL) {
                lp = LinearLayout.LayoutParams(icon, side)
                lp.topMargin = (8 * d).toInt()
                idx = parent.indexOfChild(anchor).let { if (it >= 0) it + 1 else parent.childCount }
            } else {
                lp = LinearLayout.LayoutParams(side, icon)
                lp.marginEnd = (4 * d).toInt()
                // right side: just before the status area
                idx = if (tray != null && tray.parent === parent) parent.indexOfChild(tray)
                else parent.indexOfChild(anchor).let { if (it >= 0) it + 1 else parent.childCount }
            }
            lp.gravity = Gravity.CENTER
            parent.addView(iv, idx, lp)
            ref = WeakReference(iv)
            iv.visibility = anchor.visibility.let { if (it == View.GONE) View.VISIBLE else it }

            // keep the sync listener alive while Taskbar is used (independent service)
            if (!ctx.getSharedPreferences("c", 0).getBoolean("stopped", false) && !SyncService.on) {
                val i = Intent(ctx, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        } catch (e: Exception) {
        }
    }

    /** Mirror Taskbar's show/hide of its start-area buttons. */
    @JvmStatic
    fun setShown(shown: Boolean) {
        ref?.get()?.visibility = if (shown) View.VISIBLE else View.GONE
    }
}
