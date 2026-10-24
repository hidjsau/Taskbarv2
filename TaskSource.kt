package com.clipsync.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.farmerbb.taskbar.util.AppEntry
import com.farmerbb.taskbar.util.U
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()

    // shizukuState() does binder calls. The UI thread must never wait on them from the
    // taskbar code, so the taskbar uses this cached copy (refreshed off the UI thread).
    @Volatile private var stateCache = 0
    @Volatile private var stateTime = 0L

    private fun shizukuStateCached(): Int {
        val now = SystemClock.elapsedRealtime()
        if (onMain() || now - stateTime < 2000L) return stateCache
        val s = shizukuState()
        stateCache = s
        stateTime = now
        return s
    }

    @Volatile private var cache: List<Task>? = null
    @Volatile private var cacheTime = 0L

    // ---- shell call: bounded, always cleaned up --------------------------------------
    private const val SHELL_TIMEOUT_MS = 4000L
    private const val MAX_STALE_MS = 30_000L

    private val shellExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskbar-shell").apply { isDaemon = true }
    }

    @Volatile private var shellFuture: Future<*>? = null
    @Volatile private var shellProc: Process? = null

    private val newProcessMethod by lazy {
        // newProcess is not public API in newer Shizuku versions, so call it reflectively
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).also { it.isAccessible = true }
    }

    /** Closes every stream and kills the process, ignoring errors. Frees the file descriptors. */
    private fun cleanup(p: Process) {
        try { p.outputStream.close() } catch (t: Throwable) {}
        try { p.inputStream.close() } catch (t: Throwable) {}
        try { p.errorStream.close() } catch (t: Throwable) {}
        try { p.destroy() } catch (t: Throwable) {}
    }

    /** Runs on the shell thread. Always closes the streams and destroys the process. */
    private fun runBlocking(cmd: Array<String>): String? {
        var p: Process? = null
        try {
            p = newProcessMethod.invoke(null, cmd, null, null) as Process
            shellProc = p
            return p.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            return null
        } finally {
            shellProc = null
            if (p != null) cleanup(p)
        }
    }

    /**
     * Runs a shell command through Shizuku with a time limit. Only one call can be in
     * flight: if an earlier call is still stuck, this returns null at once instead of
     * piling up threads and processes.
     */
    private fun run(cmd: Array<String>): String? {
        val prev = shellFuture
        if (prev != null && !prev.isDone) return null
        val task = try {
            shellExec.submit(Callable<String?> { runBlocking(cmd) })
        } catch (t: Throwable) {
            return null
        }
        shellFuture = task
        return try {
            task.get(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            // Shizuku stalled: kill the process so the reader unblocks and the thread frees up
            shellProc?.let { cleanup(it) }
            task.cancel(true)
            null
        } catch (t: Throwable) {
            null
        }
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

    // ---- Direct binder call (fast path) ----------------------------------------------
    // Asks the system's activity task service for the recent tasks through Shizuku. No process
    // is started and nothing is parsed, so it takes a few milliseconds instead of a few hundred.
    // Any problem (other Android version, hidden API change, Shizuku restart) makes it return
    // null, and the caller falls back to the dumpsys shell command below.

    private const val RECENT_IGNORE_UNAVAILABLE = 0x2
    private const val BINDER_MAX_TASKS = 50
    private const val BINDER_FAIL_LIMIT = 3
    private const val BINDER_RETRY_MS = 60_000L

    private var binderService: Any? = null
    private var binderMethod: Method? = null
    private var binderFails = 0
    private var binderRetryAt = 0L
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFields = ConcurrentHashMap<String, Boolean>()

    private fun resetBinder() {
        binderService = null
        binderMethod = null
    }

    private fun initBinder(): Boolean {
        U.allowReflection()
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val serviceName = if (modern) "activity_task" else "activity"
        val ifaceName = if (modern) "android.app.IActivityTaskManager" else "android.app.IActivityManager"

        val raw = SystemServiceHelper.getSystemService(serviceName) ?: return false
        val wrapped = ShizukuBinderWrapper(raw)
        val stub = Class.forName("$ifaceName\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            ?: return false

        val method = Class.forName(ifaceName).methods.firstOrNull { m ->
            m.name == "getRecentTasks" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: return false

        binderService = service
        binderMethod = method
        return true
    }

    /** Reads a (possibly hidden) field of RecentTaskInfo; null when it does not exist. */
    private fun field(info: Any, name: String): Any? {
        if (missingFields.containsKey(name)) return null
        return try {
            val f = fieldCache.getOrPut(name) { info.javaClass.getField(name) }
            f.get(info)
        } catch (e: NoSuchFieldException) {
            missingFields[name] = true
            null
        } catch (e: Throwable) {
            null
        }
    }

    private fun packageOf(info: Any): String? {
        for (n in arrayOf("baseActivity", "realActivity", "origActivity", "topActivity")) {
            val comp = field(info, n) as? ComponentName
            if (comp != null) return comp.packageName
        }
        val intent = field(info, "baseIntent") as? Intent
        val fromIntent = intent?.component?.packageName
        if (fromIntent != null) return fromIntent
        // last resort, same idea as the "A=" part of the dumpsys line: "<userId>:<package>"
        val affinity = field(info, "affinity") as? String
        if (affinity != null) {
            val p = affinity.substringAfter(':')
            if (p.isNotEmpty()) return p
        }
        return null
    }

    private fun fetchViaBinder(): List<Task>? {
        val now = SystemClock.elapsedRealtime()
        if (now < binderRetryAt) return null
        try {
            if (binderMethod == null && !initBinder()) throw IllegalStateException("binder init")
            val slice = binderMethod!!.invoke(
                binderService, BINDER_MAX_TASKS, RECENT_IGNORE_UNAVAILABLE, 0
            ) ?: throw IllegalStateException("no result")
            val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>

            val tasks = ArrayList<Task>(list.size)
            for (info in list) {
                if (info == null) continue
                // other users (work profile etc.) are not shown, same as before
                val user = field(info, "userId") as? Int
                if (user != null && user != 0) continue
                val pkg = packageOf(info) ?: continue
                val visible = (field(info, "isVisible") as? Boolean) ?: false
                tasks.add(Task(pkg, visible, ""))
            }
            binderFails = 0
            return tasks
        } catch (t: Throwable) {
            // forget the connection (Shizuku may have restarted); after a few failures in a
            // row stop trying for a minute and use the shell command instead
            resetBinder()
            binderFails++
            if (binderFails >= BINDER_FAIL_LIMIT) {
                binderFails = 0
                binderRetryAt = now + BINDER_RETRY_MS
            }
            return null
        }
    }

    private fun fetchViaShell(): List<Task>? {
        val out = run(arrayOf("sh", "-c", "dumpsys activity recents 2>/dev/null"))
        if (out.isNullOrBlank()) return null
        return parseRecents(out)
    }

    private const val SRC_BINDER = 1
    private const val SRC_SHELL = 2
    private const val BINDER_CACHE_MS = 300L
    private const val SHELL_CACHE_MS = 1000L
    @Volatile private var lastSource = SRC_SHELL
    private val fetchLock = Any()

    /**
     * Real task list from Shizuku, newest first; null when Shizuku can't be used.
     * A failed, timed-out or busy refresh keeps returning the last good list (for up to
     * MAX_STALE_MS), so the taskbar never goes blank just because one refresh was slow.
     */
    @JvmStatic
    fun shizukuTasks(ctx: Context): List<Task>? {
        if (shizukuStateCached() != 2) return null
        // never block the UI thread: it only ever gets the last result
        if (onMain()) return cache

        val now = SystemClock.elapsedRealtime()
        val c = cache
        val ttl = if (lastSource == SRC_BINDER) BINDER_CACHE_MS else SHELL_CACHE_MS
        if (c != null && now - cacheTime < ttl) return c

        return synchronized(fetchLock) {
            // another thread may have refreshed while we waited for the lock
            val c2 = cache
            val t2 = SystemClock.elapsedRealtime()
            if (c2 != null && t2 - cacheTime < ttl) return@synchronized c2

            var fresh = fetchViaBinder()
            if (fresh != null) {
                lastSource = SRC_BINDER
            } else {
                fresh = fetchViaShell()
                if (fresh != null) lastSource = SRC_SHELL
            }

            if (fresh == null) {
                // keep the last good list; only give up (use the estimate) after a long time
                if (c2 != null && t2 - cacheTime < MAX_STALE_MS) c2 else null
            } else {
                cache = fresh
                cacheTime = SystemClock.elapsedRealtime()
                fresh
            }
        }
    }

    // ---- Usage-event estimate (no Shizuku) ---------------------------------------------

    // All state below is guarded by stateLock. The lock is only ever held for short,
    // in-memory work. Slow system calls (queryEvents, getApplicationInfo) run outside it.
    private val stateLock = Any()
    private val lastSeen = HashMap<String, Long>()
    private val foreground = LinkedHashSet<String>()
    private var lastEventTime = 0L
    private var lastQuery = 0L

    private val scanning = AtomicBoolean(false)
    @Volatile private var lastScanAttempt = 0L
    private const val SCAN_MIN_INTERVAL_MS = 800L

    private class Ev(val pkg: String, val type: Int, val time: Long)

    /** Reads new usage events. Skipped on the UI thread; one scan at a time. */
    @Suppress("DEPRECATION")
    private fun scanEvents(ctx: Context) {
        if (onMain()) return
        val t = SystemClock.elapsedRealtime()
        if (t - lastScanAttempt < SCAN_MIN_INTERVAL_MS) return
        if (!scanning.compareAndSet(false, true)) return
        try {
            lastScanAttempt = t
            val now = System.currentTimeMillis()
            val from: Long
            val lastEv: Long
            synchronized(stateLock) {
                from = if (lastQuery == 0L) now - 6L * 3600_000L else lastQuery
                lastEv = lastEventTime
            }

            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(from, now) ?: return   // slow call, no lock held
            val e = UsageEvents.Event()
            val own = ctx.packageName
            val batch = ArrayList<Ev>()
            var newest = lastEv
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.timeStamp <= newest) continue
                newest = e.timeStamp
                val pkg = e.packageName ?: continue
                if (pkg == own) continue
                batch.add(Ev(pkg, e.eventType, e.timeStamp))
            }

            synchronized(stateLock) {
                for (ev in batch) {
                    when (ev.type) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            foreground.remove(ev.pkg)
                            foreground.add(ev.pkg)
                            lastSeen[ev.pkg] = ev.time
                        }
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            foreground.remove(ev.pkg)
                            lastSeen[ev.pkg] = ev.time
                        }
                    }
                }
                if (newest > lastEventTime) lastEventTime = newest
                lastQuery = now
            }
        } catch (e: Exception) {
        } finally {
            scanning.set(false)
        }
    }

    // getApplicationInfo is a binder call, so remember the answer for a few seconds
    private val stoppedCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val STOPPED_TTL_MS = 15_000L

    private fun isStopped(ctx: Context, pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val hit = stoppedCache[pkg]
        if (hit != null && now - hit.second < STOPPED_TTL_MS) return hit.first
        val stopped = try {
            (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
        } catch (e: Exception) {
            false
        }
        stoppedCache[pkg] = Pair(stopped, now)
        return stopped
    }

    private fun estimate(ctx: Context): List<String> {
        scanEvents(ctx)
        val now = System.currentTimeMillis()
        val ttl = U.getSharedPreferences(ctx).getInt(PREF_TTL_MIN, DEFAULT_TTL_MIN) * 60_000L

        // copy the state under the lock, then do the slow filtering outside it
        val seen: List<Pair<String, Long>>
        val fg: Set<String>
        synchronized(stateLock) {
            seen = lastSeen.entries.map { Pair(it.key, it.value) }
            fg = HashSet(foreground)
        }
        return seen
            .filter { fg.contains(it.first) || now - it.second < ttl }
            .filter { !isStopped(ctx, it.first) }
            .sortedByDescending { if (fg.contains(it.first)) Long.MAX_VALUE else it.second }
            .map { it.first }
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
        return synchronized(stateLock) { foreground.lastOrNull() }
    }

    // Stable (Windows style) order: apps keep the position they got when they were opened.
    // Switching between apps never moves an icon; new apps join at the end.
    private val openOrder = ArrayList<String>()
    private val lastPresent = HashMap<String, Long>()
    private const val GONE_GRACE_MS = 3000L
    @Volatile private var lastOrdered: List<String> = emptyList()

    /**
     * Entries for the Taskbar in opening order (first opened first), or null to use
     * Taskbar's normal list. The caller assigns the sort times.
     */
    @JvmStatic
    fun getOpenEntries(ctx: Context): List<AppEntry>? {
        if (!enabled(ctx)) return null
        // the UI thread only gets the last result; all slow work happens on the refresh thread
        if (onMain()) return lastOrdered.map { p -> AppEntry(p, null, null, null, false) }

        val own = ctx.packageName
        val tasks = shizukuTasks(ctx)
        // newest first
        val pkgs = if (tasks != null) {
            tasks.map { it.pkg }.filter { it != own }.distinct()
        } else {
            estimate(ctx)
        }
        val now = System.currentTimeMillis()

        val ordered: List<String> = synchronized(stateLock) {
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

        lastOrdered = ordered
        return ordered.map { p -> AppEntry(p, null, null, null, false) }
    }
}
