package com.clipsync.app

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class SendActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var started = false
    private var tries = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            poll()
        }
    }

    private fun read(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c == null || c.itemCount == 0) return null
        return c.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun poll() {
        val t = read()
        if (t != null && t.isNotEmpty()) {
            if (Net.send(this, t)) {
                Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Pair a device first", Toast.LENGTH_SHORT).show()
            }
            end()
            return
        }
        tries++
        if (tries < 5) {
            h.postDelayed({ poll() }, 80)
        } else {
            Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            end()
        }
    }

    private fun end() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
