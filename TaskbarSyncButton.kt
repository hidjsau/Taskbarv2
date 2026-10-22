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
 * Adds a "Sync" button to the Taskbar start area, next to the Dashboard button.
 * Called from TaskbarController (patched in by patch_taskbar.py).
 * Everything here is optional: if the toggle is off, nothing is added.
 */
object TaskbarSyncButton {
    private var ref: WeakReference<View>? = null

    @JvmStatic
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("c", 0).getBoolean("tbbutton", true)

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
            val pad = (8 * ctx.resources.displayMetrics.density).toInt()
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

            val dp8 = pad
            val side = (40 * ctx.resources.displayMetrics.density).toInt()
            val icon = ctx.resources.getDimensionPixelSize(R.dimen.tb_icon_size)
            val lp: LinearLayout.LayoutParams
            if (parent.orientation == LinearLayout.VERTICAL) {
                lp = LinearLayout.LayoutParams(icon, side)
                lp.topMargin = dp8
            } else {
                lp = LinearLayout.LayoutParams(side, icon)
                lp.marginEnd = dp8
            }
            lp.gravity = Gravity.CENTER
            val idx = parent.indexOfChild(anchor)
            parent.addView(iv, if (idx >= 0) idx + 1 else parent.childCount, lp)
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
