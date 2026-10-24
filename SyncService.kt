package com.clipsync.app

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.io.DataInputStream
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SyncService : Service() {
    companion object {
        @Volatile
        var on = false
    }

    @Volatile
    private var running = false

    // Counts service starts. Network threads from an older start see a new number and end.
    @Volatile
    private var epoch = 0

    // Wakes the heartbeat thread early (screen turned on, service stopping)
    private val hbWake = Semaphore(0)

    // Received packets are handled on a worker thread, so the thread that reads from the
    // network is never held up by decrypting or saving (it used to miss packets in bursts).
    // If the queue is ever full, the receiving thread does the work itself.
    private var worker: ExecutorService? = null

    // Incoming TCP connections: at most 4 at a time and 8 waiting, anything more is refused.
    private var tcpPool: ExecutorService? = null
    private val MAX_TCP_MESSAGE = 100_000
    private val HEARTBEAT_MS = 20_000L

    private fun screenOn(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    // The multicast lock keeps the WiFi radio busy, so it is only held while the screen is on.
    // Direct (TCP / unicast) messages do not need it.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Net.invalidateAddresses()
                        lock?.acquire()
                        hbWake.release()   // announce ourselves right away
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        if (lock?.isHeld == true) lock?.release()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
    private var sock: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var bubble: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val recent = LinkedHashMap<Long, Long>()

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
            val fresh = !partials.containsKey(key)
            val p = partials.getOrPut(key) { Partial(total) }
            if (p.total != total) return
            if (fresh) {
                main.postDelayed({
                    synchronized(partials) {
                        partials[key]?.let {
                            Toast.makeText(
                                this, "Incomplete message: got ${it.got} of ${it.total} pieces",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }, 10000)
            }
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

    // The same packet can arrive more than once (e.g. on several networks).
    // Every packet starts with a random 12-byte IV, so the first 8 bytes identify a packet.
    // The map keeps insertion order, so expired entries are always at the front.
    private fun isDuplicate(data: ByteArray): Boolean {
        if (data.size < 12) return false
        var key = 0L
        for (i in 0 until 8) key = (key shl 8) or (data[i].toLong() and 0xff)
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val it = recent.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > 30000 || recent.size > 2000) it.remove() else break
            }
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
            if (screenOn()) lock?.acquire()
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, f)
            }
            epoch++
            val my = epoch
            worker = ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512),
                { r -> Thread(r, "clipsync-rx").apply { isDaemon = true } },
                ThreadPoolExecutor.CallerRunsPolicy()
            )
            tcpPool = ThreadPoolExecutor(
                4, 4, 10L, TimeUnit.SECONDS, ArrayBlockingQueue(8),
                { r -> Thread(r, "clipsync-tcp").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()
            )
            // work out the keys now, so the first message does not have to wait for it
            thread {
                try {
                    Crypto.prepare(Net.myPin(this))
                    for (p in Peers.list(this)) Crypto.prepare(p.pin)
                } catch (e: Exception) {
                }
            }
            listen(my)
            listenTcp(my)
            heartbeat(my)
        }
        if (getSharedPreferences("c", 0).getBoolean("bubble", false)) showBubble() else hideBubble()
        return START_STICKY
    }

    private fun active(my: Int) = running && epoch == my

    // Every 20 s while the screen is on. A device that is online is addressed directly
    // (unicast); only unknown or silent devices get a broadcast.
    private fun heartbeat(my: Int) {
        thread {
            while (active(my)) {
                try {
                    if (screenOn()) {
                        val id = Net.myId(this)
                        val name = Net.myName()
                        for (p in Peers.list(this)) {
                            val ip = if (Peers.online(p.id)) Peers.ip[p.id] else null
                            Net.sendTo(p.pin, "H|$id|$name|", ip)
                        }
                    }
                } catch (e: Exception) {
                }
                try {
                    hbWake.tryAcquire(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                }
                hbWake.drainPermits()
            }
        }
    }

    // Both listeners start again after an error (with a growing pause) instead of dying silently.
    private fun listen(my: Int) {
        thread {
            var pause = 1000L
            while (active(my)) {
                var s: DatagramSocket? = null
                try {
                    s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(Net.PORT))
                    sock = s
                    pause = 1000L
                    val buf = ByteArray(65535)
                    while (active(my)) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val data = p.data.copyOf(p.length)
                        val from = p.address?.hostAddress
                        val task = Runnable {
                            try {
                                handle(data, from)
                            } catch (e: Exception) {
                                // one bad packet must not stop anything
                            }
                        }
                        val w = worker
                        if (w != null) w.execute(task) else task.run()
                    }
                } catch (e: Exception) {
                } finally {
                    try {
                        s?.close()
                    } catch (e: Exception) {
                    }
                }
                if (!active(my)) break
                try {
                    Thread.sleep(pause)
                } catch (e: InterruptedException) {
                    break
                }
                pause = minOf(pause * 2, 10_000L)
            }
        }
    }

    private fun listenTcp(my: Int) {
        thread {
            var pause = 1000L
            while (active(my)) {
                var ss: ServerSocket? = null
                try {
                    ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(Net.TCP_PORT))
                    server = ss
                    pause = 1000L
                    while (active(my)) {
                        val c = ss.accept()
                        // only devices on a local network (home WiFi, hotspot) may connect
                        val addr = c.inetAddress
                        val local = addr != null &&
                            (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)
                        val pool = tcpPool
                        if (!local || pool == null) {
                            try {
                                c.close()
                            } catch (e: Exception) {
                            }
                            continue
                        }
                        try {
                            pool.execute {
                                try {
                                    c.soTimeout = 5000
                                    val i = DataInputStream(c.getInputStream())
                                    val n = i.readInt()
                                    if (n in 13..MAX_TCP_MESSAGE) {
                                        val b = ByteArray(n)
                                        i.readFully(b)
                                        handle(b, addr.hostAddress)
                                    }
                                } catch (e: Exception) {
                                } finally {
                                    try {
                                        c.close()
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // too many connections at once: refuse this one
                            try {
                                c.close()
                            } catch (e2: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    try {
                        ss?.close()
                    } catch (e: Exception) {
                    }
                }
                if (!active(my)) break
                try {
                    Thread.sleep(pause)
                } catch (e: InterruptedException) {
                    break
                }
                pause = minOf(pause * 2, 10_000L)
            }
        }
    }

    private fun handle(data: ByteArray, from: String?) {
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
        if (from != null) Peers.ip[id] = from
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
        epoch++
        on = false
        hbWake.release()
        worker?.shutdownNow()
        tcpPool?.shutdownNow()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
        }
        sock?.close()
        server?.close()
        try {
            if (lock?.isHeld == true) lock?.release()
        } catch (e: Exception) {
        }
        hideBubble()
        super.onDestroy()
    }
}
