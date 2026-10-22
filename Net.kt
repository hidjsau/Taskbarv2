package com.clipsync.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

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

    private fun clean(s: String) = s.replace(Regex("[\t\n|]"), " ")

    @Synchronized
    fun list(ctx: Context): List<Peer> {
        val raw = ctx.getSharedPreferences("c", 0).getString("peers", "") ?: ""
        return raw.split("\n").mapNotNull { line ->
            val f = line.split("\t")
            if (f.size == 3) Peer(f[0], f[1], f[2]) else null
        }
    }

    @Synchronized
    private fun save(ctx: Context, l: List<Peer>) {
        val s = l.joinToString("\n") { "${it.id}\t${it.name}\t${it.pin}" }
        ctx.getSharedPreferences("c", 0).edit().putString("peers", s).apply()
    }

    @Synchronized
    fun add(ctx: Context, p: Peer) {
        save(ctx, list(ctx).filter { it.id != p.id } + Peer(p.id, clean(p.name), p.pin))
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
        seen.remove(id)
    }

    fun has(ctx: Context, id: String): Boolean = list(ctx).any { it.id == id }

    fun online(id: String): Boolean =
        System.currentTimeMillis() - (seen[id] ?: 0L) < 25000
}

object History {
    @Volatile
    var version = 0

    @Synchronized
    fun load(ctx: Context): List<Msg> {
        val raw = ctx.getSharedPreferences("h", 0).getString("l", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Msg(o.getBoolean("s"), o.getString("t"), o.getString("w"), o.getLong("m"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(ctx: Context, sent: Boolean, text: String, who: String) {
        val all = load(ctx) + Msg(sent, text, who, System.currentTimeMillis())
        val keep = (all.filter { it.sent }.takeLast(15) + all.filter { !it.sent }.takeLast(15))
            .sortedBy { it.time }
        val a = JSONArray()
        for (m in keep) {
            val o = JSONObject()
            o.put("s", m.sent)
            o.put("t", m.text)
            o.put("w", m.who)
            o.put("m", m.time)
            a.put(o)
        }
        ctx.getSharedPreferences("h", 0).edit().putString("l", a.toString()).apply()
        version++
    }
}

object Net {
    const val PORT = 48555
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

    fun broadcast(data: ByteArray) {
        try {
            val s = DatagramSocket()
            s.broadcast = true
            try {
                s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT))
            } catch (e: Exception) {
            }
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs != null && ifs.hasMoreElements()) {
                    val n = ifs.nextElement()
                    if (!n.isUp || n.isLoopback) continue
                    for (a in n.interfaceAddresses) {
                        val b = a.broadcast ?: continue
                        try {
                            s.send(DatagramPacket(data, data.size, b, PORT))
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
            s.close()
        } catch (e: Exception) {
        }
    }

    fun sendTo(pin: String, plain: String) = broadcast(Crypto.enc(pin, plain))

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
        thread {
            for (p in peers) sendTo(p.pin, "T|$id|$name|$t")
        }
        History.add(ctx, true, t, peers.joinToString(", ") { it.name })
        return true
    }
}
