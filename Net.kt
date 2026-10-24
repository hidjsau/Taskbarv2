package com.clipsync.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

fun Context.canOverlay(): Boolean =
    Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.bg(color: Int, r: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    return g
}

fun Context.tv(t: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    if (bold) v.setTypeface(null, Typeface.BOLD)
    return v
}

class Peer(val id: String, val name: String, val pin: String)
class Msg(val sent: Boolean, val text: String, val who: String, val time: Long)

object Peers {
    val seen = ConcurrentHashMap<String, Long>()
    val ip = ConcurrentHashMap<String, String>()

    private val dirty = Regex("[\t\n|]")
    private fun clean(s: String) = s.replace(dirty, " ")

    // The list is read from settings once and kept in memory (it used to be parsed again
    // for every single packet).
    private var cached: List<Peer>? = null

    @Synchronized
    fun list(ctx: Context): List<Peer> {
        cached?.let { return it }
        val raw = ctx.getSharedPreferences("c", 0).getString("peers", "") ?: ""
        val l = raw.split("\n").mapNotNull { line ->
            val f = line.split("\t")
            if (f.size == 3) Peer(f[0], f[1], f[2]) else null
        }
        cached = l
        return l
    }

    @Synchronized
    private fun save(ctx: Context, l: List<Peer>) {
        cached = l
        val s = l.joinToString("\n") { "${it.id}\t${it.name}\t${it.pin}" }
        ctx.getSharedPreferences("c", 0).edit().putString("peers", s).apply()
    }

    @Synchronized
    fun add(ctx: Context, p: Peer) {
        val old = list(ctx).firstOrNull { it.id == p.id }
        val name = clean(p.name)
        // already known and unchanged (this happens on every pairing packet): nothing to save
        if (old != null && old.name == name && old.pin == p.pin) return
        save(ctx, list(ctx).filter { it.id != p.id } + Peer(p.id, name, p.pin))
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
        seen.remove(id)
        ip.remove(id)
    }

    fun has(ctx: Context, id: String): Boolean = list(ctx).any { it.id == id }

    fun online(id: String): Boolean =
        System.currentTimeMillis() - (seen[id] ?: 0L) < 50000
}

object History {
    @Volatile
    var version = 0

    // The list is kept in memory. It is written to settings later, in the background, at most
    // once a second, so receiving a message never waits for the file (and the main thread is
    // never held up by a pending write).
    private var cache: List<Msg>? = null
    private var writePending = false
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "clipsync-history").apply { isDaemon = true }
    }

    @Synchronized
    fun load(ctx: Context): List<Msg> {
        cache?.let { return it }
        val raw = ctx.getSharedPreferences("h", 0).getString("l", "[]") ?: "[]"
        val l = try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Msg(o.getBoolean("s"), o.getString("t"), o.getString("w"), o.getLong("m"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache = l
        return l
    }

    @Synchronized
    fun add(ctx: Context, sent: Boolean, text: String, who: String) {
        val all = load(ctx) + Msg(sent, text, who, System.currentTimeMillis())
        cache = (all.filter { it.sent }.takeLast(15) + all.filter { !it.sent }.takeLast(15))
            .sortedBy { it.time }
        version++
        scheduleWrite(ctx.applicationContext)
    }

    private fun scheduleWrite(app: Context) {
        if (writePending) return
        writePending = true
        writer.execute {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
            }
            val snapshot: List<Msg>?
            synchronized(this) {
                writePending = false
                snapshot = cache
            }
            if (snapshot != null) {
                val a = JSONArray()
                for (m in snapshot) {
                    val o = JSONObject()
                    o.put("s", m.sent)
                    o.put("t", m.text)
                    o.put("w", m.who)
                    o.put("m", m.time)
                    a.put(o)
                }
                try {
                    app.getSharedPreferences("h", 0).edit().putString("l", a.toString()).commit()
                } catch (e: Exception) {
                }
            }
        }
    }
}

object Net {
    const val PORT = 48555
    const val TCP_PORT = 48556
    @Volatile
    var lastClip = ""
    private var lastText = ""
    private var lastTime = 0L
    private var cachedId: String? = null

    fun myId(ctx: Context): String {
        cachedId?.let { return it }
        val p = ctx.getSharedPreferences("c", 0)
        var v = p.getString("myid", null)
        if (v == null) {
            v = UUID.randomUUID().toString().substring(0, 8)
            p.edit().putString("myid", v).apply()
        }
        cachedId = v
        return v
    }

    fun myPin(ctx: Context): String {
        val p = ctx.getSharedPreferences("c", 0)
        var v = p.getString("mypin", null)
        if (v == null) {
            v = "%04d".format(SecureRandom().nextInt(10000))
            p.edit().putString("mypin", v).apply()
        }
        return v
    }

    fun myName(): String = Build.MODEL.replace("|", " ")

    // One UDP socket is reused for every packet (it used to be created and closed per packet).
    @Volatile
    private var sendSock: DatagramSocket? = null

    @Synchronized
    private fun socket(): DatagramSocket {
        sendSock?.let { if (!it.isClosed) return it }
        val s = DatagramSocket()
        s.broadcast = true
        sendSock = s
        return s
    }

    @Synchronized
    private fun resetSocket() {
        try {
            sendSock?.close()
        } catch (e: Exception) {
        }
        sendSock = null
    }

    // Broadcast addresses of the networks that are up. Looked up at most every 15 s
    // instead of before every packet.
    private const val ADDR_TTL_MS = 15_000L
    @Volatile
    private var bcastAddrs: List<InetAddress> = emptyList()
    @Volatile
    private var bcastTime = 0L

    fun invalidateAddresses() {
        bcastTime = 0L
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = bcastAddrs
        if (cached.isNotEmpty() && now - bcastTime < ADDR_TTL_MS) return cached
        val found = ArrayList<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val n = ifs.nextElement()
                if (!n.isUp || n.isLoopback) continue
                for (a in n.interfaceAddresses) {
                    val b = a.broadcast ?: continue
                    if (!found.contains(b)) found.add(b)
                }
            }
        } catch (e: Exception) {
        }
        bcastAddrs = found
        bcastTime = now
        return found
    }

    /** Sends one packet to the broadcast address of every network (once each). */
    fun broadcast(data: ByteArray) {
        var sent = false
        try {
            val s = socket()
            for (a in broadcastAddresses()) {
                try {
                    s.send(DatagramPacket(data, data.size, a, PORT))
                    sent = true
                } catch (e: Exception) {
                }
            }
            if (!sent) {
                // no network address found: fall back to the global broadcast address
                try {
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT))
                    sent = true
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        if (!sent) {
            // network probably changed: start fresh next time
            invalidateAddresses()
            resetSocket()
        }
    }

    /** Sends straight to one device. False when it could not be sent. */
    private fun unicast(ip: String, data: ByteArray): Boolean = try {
        socket().send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
        true
    } catch (e: Exception) {
        resetSocket()
        false
    }

    /** [ip] given: send straight to that device (no broadcast). Otherwise, or if that fails: broadcast. */
    fun sendTo(pin: String, plain: String, ip: String? = null) {
        val data = Crypto.enc(pin, plain)
        if (ip != null && unicast(ip, data)) return
        broadcast(data)
    }

    private const val CHUNK = 400

    /** Short text goes as one packet. Long text goes as many small packets (no IP fragmentation). */
    private fun sendTcp(addr: String, data: ByteArray): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(addr, TCP_PORT), 1500)
            s.soTimeout = 8000
            val o = DataOutputStream(s.getOutputStream())
            o.writeInt(data.size)
            o.write(data)
            o.flush()
        }
        true
    } catch (e: Exception) {
        false
    }

    fun sendText(peer: Peer, id: String, name: String, t: String) {
        val pin = peer.pin
        // 1) direct connection to the other device: reliable, no size problem
        val addr = Peers.ip[peer.id]
        if (addr != null && sendTcp(addr, Crypto.enc(pin, "T|$id|$name|$t"))) return
        // 2) fallback: broadcast (long text split into small pieces)
        if (t.length <= CHUNK) {
            sendTo(pin, "T|$id|$name|$t")
            return
        }
        val parts = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            var end = minOf(i + CHUNK, t.length)
            // do not cut an emoji (surrogate pair) in half
            if (end < t.length && Character.isHighSurrogate(t[end - 1])) end--
            parts.add(t.substring(i, end))
            i = end
        }
        val mid = UUID.randomUUID().toString().substring(0, 6)
        // two rounds: lost packets in round one are filled in by round two
        for (round in 0 until 2) {
            for ((n, p) in parts.withIndex()) {
                sendTo(pin, "C|$id|$name|$mid:$n:${parts.size}:$p")
                Thread.sleep(2)
            }
            Thread.sleep(150)
        }
    }

    // A few worker threads send to the paired devices in parallel, so one device that is off
    // does not delay the others (and no new thread is created per message).
    private val sender = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "clipsync-send").apply { isDaemon = true }
    }

    fun send(ctx: Context, text: String): Boolean {
        lastClip = text
        val peers = Peers.list(ctx)
        if (peers.isEmpty()) return false
        val t = if (text.length > 15000) text.substring(0, 15000) else text
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (t == lastText && now - lastTime < 3000) return true
            lastText = t
            lastTime = now
        }
        val id = myId(ctx)
        val name = myName()
        for (p in peers) {
            sender.execute {
                try {
                    sendText(p, id, name, t)
                } catch (e: Exception) {
                }
            }
        }
        History.add(ctx, true, t, peers.joinToString(", ") { it.name })
        return true
    }
}
