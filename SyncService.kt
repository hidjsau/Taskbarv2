package com.clipsync.app

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import com.farmerbb.taskbar.R
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class SyncService : Service() {
    companion object {
        @Volatile
        var on = false
    }

    private var running = false
    private var sock: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var bubble: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val recent = HashMap<String, Long>()

    private class Partial(val total: Int) {
        val parts = arrayOfNulls<String>(total)
        var got = 0
        val born = System.currentTimeMillis()
    }

    private val partials = HashMap<String, Partial>()
    private val finished = HashMap<String, Long>()

    private fun deliver(name: String, text: String) {
        Net.lastClip = text
        History.add(this, false, text, name)
        main.post {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sync", text))
            Toast.makeText(this, "Synced from $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chunk(id: String, name: String, rest: String) {
        val q = rest.split(":", limit = 4)
        if (q.size < 4) return
        val idx = q[1].toIntOrNull() ?: return
        val total = q[2].toIntOrNull() ?: return
        if (total < 1 || total > 500 || idx < 0 || idx >= total) return
        val key = "$id:${q[0]}"
        var text: String? = null
        val now = System.currentTimeMillis()
        synchronized(partials) {
            partials.entries.removeAll { now - it.value.born > 60000 }
            finished.entries.removeAll { now - it.value > 120000 }
            if (finished.containsKey(key)) return
            val p = partials.getOrPut(key) { Partial(total) }
            if (p.total != total) return
            if (p.parts[idx] == null) {
                p.parts[idx] = q[3]
                p.got++
            }
            if (p.got == p.total) {
                text = p.parts.joinToString("")
                partials.remove(key)
                finished[key] = now
            }
        }
        text?.let { deliver(name, it) }
    }

    // The same packet can arrive more than once (it is broadcast on several addresses).
    // Every packet has a random 12-byte IV, so identical IV = identical packet.
    private fun isDuplicate(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val key = data.copyOfRange(0, 12).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 30000 }
            if (recent.containsKey(key)) return true
            recent[key] = now
        }
        return false
    }

    override fun onCreate() {
        super.onCreate()
        on = true
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("sync", "ClipSync", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "sync")
        else Notification.Builder(this)
        n.setContentTitle("ClipSync running")
        n.setSmallIcon(android.R.drawable.stat_notify_sync)
        n.setOngoing(true)
        val sendPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        n.setContentIntent(openPi)
        if (Build.VERSION.SDK_INT >= 23) {
            n.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_sync), "Send clipboard", sendPi
                ).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n.build())
        }
        if (!running) {
            running = true
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("clipsync")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            listen()
            heartbeat()
        }
        if (getSharedPreferences("c", 0).getBoolean("bubble", false)) showBubble() else hideBubble()
        return START_STICKY
    }

    private fun heartbeat() {
        thread {
            while (running) {
                val id = Net.myId(this)
                for (p in Peers.list(this)) {
                    Net.sendTo(p.pin, "H|$id|${Net.myName()}|")
                }
                try {
                    Thread.sleep(8000)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    private fun listen() {
        thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(Net.PORT))
                sock = s
                val buf = ByteArray(65535)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handle(p.data.copyOf(p.length))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun handle(data: ByteArray) {
        val pin = Net.myPin(this)
        val plain = Crypto.dec(pin, data) ?: return
        if (isDuplicate(data)) return
        val f = plain.split("|", limit = 4)
        if (f.size < 4) return
        val id = f[1]
        if (id == Net.myId(this)) return
        val name = f[2]
        val rest = f[3]
        val now = System.currentTimeMillis()
        when (f[0]) {
            "P" -> if (rest.length == 4) {
                Peers.add(this, Peer(id, name, rest))
                Peers.seen[id] = now
                Net.sendTo(rest, "A|${Net.myId(this)}|${Net.myName()}|$pin")
            }
            "A" -> if (rest.length == 4) {
                Peers.add(this, Peer(id, name, rest))
                Peers.seen[id] = now
            }
            "H" -> if (Peers.has(this, id)) {
                Peers.seen[id] = now
            }
            "T" -> if (Peers.has(this, id)) {
                Peers.seen[id] = now
                deliver(name, rest)
            }
            "C" -> if (Peers.has(this, id)) {
                Peers.seen[id] = now
                chunk(id, name, rest)
            }
        }
    }

    private fun showBubble() {
        if (bubble != null || !canOverlay()) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()
        val v = TextView(this)
        v.text = "Sync"
        v.setTextColor(Color.WHITE)
        v.textSize = 12f
        v.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xCC1976D2.toInt())
        v.background = bg
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 300
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = sx + dx
                        lp.y = sy + dy
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(
                            Intent(this, SendActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun hideBubble() {
        bubble?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
    }

    override fun onDestroy() {
        running = false
        on = false
        sock?.close()
        lock?.release()
        hideBubble()
        super.onDestroy()
    }
}
