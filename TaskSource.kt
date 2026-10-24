package com.clipsync.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import com.farmerbb.taskbar.util.AppEntry
import com.farmerbb.taskbar.util.U
import rikka.shizuku.Shizuku

/**
 * "Open apps" list for the Taskbar (Windows style instead of a usage history).
 *
 *  - Shizuku running + allowed: the real task list (cleared apps vanish at once).
 *  - Otherwise (Shizuku is optional): an estimate from usage events, i.e. apps seen in
 *    the foreground within the last TTL minutes, minus force-stopped apps.
 *
 * Called from TaskbarController.getAppEntries (returns null when the mode is switched off,
 * so Taskbar's normal list is used).
 */
object TaskSource {
    const val PREF_ENABLED = "clipsync_open_apps"
    const val PREF_TTL_MIN = "clipsync_open_apps_ttl_min"
    private const val DEFAULT_TTL_MIN = 120

    data class Task(val pkg: String, val visible: Boolean, val mode: String)

    @JvmStatic
    fun enabled(ctx: Context): Boolean =
        U.getSharedPreferences(ctx).getBoolean(PREF_ENABLED, true)

    // ---- Shizuku -------------------------------------------------------------------

    /** 0 = not running / unsupported, 1 = running but not allowed yet, 2 = ready. */
    @JvmStatic
    fun shizukuState(): Int = try {
        when {
            !Shizuku.pingBinder() -> 0
            Shizuku.isPreV11() -> 0
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
            else -> 1
        }
    } catch (t: Throwable) {
        0
    }

    private var cache: List<Task>? = null
    private var cacheTime = 0L

    private fun run(cmd: Array<String>): String? = try {
        // newProcess is not public API in newer Shizuku versions, so call it reflectively
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (t: Throwable) {
        null
    }

    private val appRe = Regex("""\bA=(?:\d+:)?([A-Za-z0-9_.]+)""")
    private val realRe = Regex("""realActivity=([A-Za-z0-9_.]+)/""")
    private val userRe = Regex("""\bU=(\d+)""")
    private val modeRe = Regex("""\bmode=(\w+)""")

    private fun parseRecents(out: String): List<Task> {
        val tasks = ArrayList<Task>()
        for (line in out.lineSequence()) {
            if (!line.contains("Recent #")) continue
            val user = userRe.find(line)?.groupValues?.get(1)
            if (user != null && user != "0") continue
            val pkg = appRe.find(line)?.groupValues?.get(1)
                ?: realRe.find(line)?.groupValues?.get(1)
                ?: continue
            tasks.add(
                Task(
                    pkg,
                    line.contains("visible=true"),
                    modeRe.find(line)?.groupValues?.get(1) ?: ""
                )
            )
        }
        return tasks
    }

    /** Real task list from Shizuku, newest first; null when Shizuku can't be used. */
    @JvmStatic
    fun shizukuTasks(ctx: Context): List<Task>? {
        if (shizukuState() != 2) return null
        val now = System.currentTimeMillis()
        if (cache != null && now - cacheTime < 1000) return cache
        // never block the UI thread on a shell call
        if (Looper.myLooper() == Looper.getMainLooper()) return cache
        val out = run(arrayOf("dumpsys", "activity", "recents"))
        if (out.isNullOrBlank()) return null
        val parsed = parseRecents(out)
        cache = parsed
        cacheTime = now
        return parsed
    }

    // ---- Usage-event estimate (no Shizuku) ---------------------------------------------

    private val lastSeen = HashMap<String, Long>()
    private val foreground = LinkedHashSet<String>()
    private var lastEventTime = 0L
    private var lastQuery = 0L

    @Synchronized
    private fun scanEvents(ctx: Context) {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (lastQuery == 0L) now - 6L * 3600_000L else lastQuery
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            val own = ctx.packageName
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.timeStamp <= lastEventTime) continue
                lastEventTime = e.timeStamp
                val pkg = e.packageName ?: continue
                if (pkg == own) continue
                @Suppress("DEPRECATION")
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        foreground.remove(pkg)
                        foreground.add(pkg)
                        lastSeen[pkg] = e.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        foreground.remove(pkg)
                        lastSeen[pkg] = e.timeStamp
                    }
                }
            }
            lastQuery = now
        } catch (e: Exception) {
        }
    }

    private fun isStopped(ctx: Context, pkg: String): Boolean = try {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: Exception) {
        false
    }

    @Synchronized
    private fun estimate(ctx: Context): List<String> {
        scanEvents(ctx)
        val now = System.currentTimeMillis()
        val ttl = U.getSharedPreferences(ctx).getInt(PREF_TTL_MIN, DEFAULT_TTL_MIN) * 60_000L
        return lastSeen.entries
            .filter { foreground.contains(it.key) || now - it.value < ttl }
            .filter { !isStopped(ctx, it.key) }
            .sortedByDescending { if (foreground.contains(it.key)) Long.MAX_VALUE else it.value }
            .map { it.key }
    }

    // ---- Public API ---------------------------------------------------------------

    /** Package with focus right now, or null if unknown. */
    @JvmStatic
    fun focused(ctx: Context): String? {
        val own = ctx.packageName
        val tasks = shizukuTasks(ctx)
        if (tasks != null) {
            val list = tasks.filter { it.pkg != own }
            return (list.firstOrNull { it.visible } ?: list.firstOrNull())?.pkg
        }
        scanEvents(ctx)
        return synchronized(this) { foreground.lastOrNull() }
    }

    // Stable (Windows style) order: apps keep the position they got when they were opened.
    // Switching between apps never moves an icon; new apps join at the end.
    private val openOrder = ArrayList<String>()
    private val lastPresent = HashMap<String, Long>()
    private const val GONE_GRACE_MS = 3000L

    /**
     * Entries for the Taskbar in opening order (first opened first), or null to use
     * Taskbar's normal list. The caller assigns the sort times.
     */
    @JvmStatic
    fun getOpenEntries(ctx: Context): List<AppEntry>? {
        if (!enabled(ctx)) return null
        val own = ctx.packageName
        val tasks = shizukuTasks(ctx)
        // newest first
        val pkgs = if (tasks != null) {
            tasks.map { it.pkg }.filter { it != own }.distinct()
        } else {
            estimate(ctx)
        }
        val now = System.currentTimeMillis()

        val ordered: List<String> = synchronized(this) {
            for (p in pkgs) lastPresent[p] = now

            // forget apps that have really gone (short grace period avoids flicker
            // when the data source briefly returns a different list)
            val it = openOrder.iterator()
            while (it.hasNext()) {
                val p = it.next()
                val seen = lastPresent[p] ?: 0L
                if (!pkgs.contains(p) && now - seen > GONE_GRACE_MS) {
                    it.remove()
                    lastPresent.remove(p)
                }
            }

            // new apps go to the end; if several are new at once, the oldest goes first
            for (p in pkgs.asReversed()) {
                if (!openOrder.contains(p)) openOrder.add(p)
            }
            ArrayList(openOrder)
        }

        return ordered.map { p -> AppEntry(p, null, null, null, false) }
    }
}
