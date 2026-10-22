package com.clipsync.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var shown = -1
    private var visible = false
    private val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    private val tick = object : Runnable {
        override fun run() {
            if (History.version != shown) render()
            if (visible) h.postDelayed(this, 1500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Messages"
        scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFFECE5DD.toInt())
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(12), dp(12), dp(24))
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        render()
        h.postDelayed(tick, 1500)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        h.removeCallbacks(tick)
    }

    private fun render() {
        shown = History.version
        list.removeAllViews()
        val msgs = History.load(this)
        if (msgs.isEmpty()) {
            val e = tv("No messages yet", 15f, false, Color.GRAY)
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(48), 0, 0)
            list.addView(e)
            return
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.72).toInt()
        for (m in msgs) list.addView(bubble(m, maxW))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: Msg, maxW: Int): View {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(dp(12), dp(8), dp(12), dp(6))
        b.background = bg(if (m.sent) 0xFFDCF8C6.toInt() else Color.WHITE, 10)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (m.sent) Gravity.END else Gravity.START
        lp.topMargin = dp(8)
        b.layoutParams = lp

        b.addView(tv(if (m.sent) "To: ${m.who}" else "From: ${m.who}", 11f, true, 0xFF00796B.toInt()))

        val msg = tv(m.text, 15f)
        msg.maxWidth = maxW
        val long = m.text.length > 250 || m.text.count { it == '\n' } > 5
        if (long) {
            msg.maxLines = 6
            msg.ellipsize = TextUtils.TruncateAt.END
        }
        b.addView(msg)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, 0)

        if (long) {
            val more = tv("Read more", 13f, true, 0xFF1976D2.toInt())
            var open = false
            more.setOnClickListener {
                open = !open
                msg.maxLines = if (open) Int.MAX_VALUE else 6
                more.text = if (open) "Show less" else "Read more"
            }
            row.addView(more)
        }
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val time = tv(fmt.format(Date(m.time)), 11f, false, Color.GRAY)
        row.addView(time)
        val copy = tv("Copy", 13f, true, 0xFF1976D2.toInt())
        copy.setPadding(dp(12), dp(4), 0, dp(4))
        copy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("msg", m.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        row.addView(copy)
        b.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return b
    }
}
