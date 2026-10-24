package com.clipsync.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import rikka.shizuku.Shizuku
import com.farmerbb.taskbar.util.U
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var permBox: LinearLayout
    private lateinit var peerBox: LinearLayout
    private lateinit var svcBtn: Button
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuBtn: Button
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> h.post { refresh() } }
    private var visible = false
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (visible) h.postDelayed(this, 3000)
        }
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = bg(Color.WHITE, 12)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        parent.addView(c, lp)
        c.addView(tv(title, 16f, true))
        return c
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFFEEEEEE.toInt())
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(32), dp(16), dp(24))
        scroll.addView(root)

        root.addView(tv("ClipSync", 26f, true))

        permBox = card(root, "Permissions")

        val openCard = card(root, "Open apps list")
        val openSw = Switch(this)
        openSw.text = "Show open apps (Windows style)"
        openSw.isChecked = TaskSource.enabled(this)
        openSw.setOnCheckedChangeListener { _, on ->
            U.getSharedPreferences(this).edit().putBoolean(TaskSource.PREF_ENABLED, on).apply()
        }
        openCard.addView(openSw)
        shizukuStatus = tv("", 13f, false, Color.GRAY)
        openCard.addView(shizukuStatus)
        shizukuBtn = Button(this)
        shizukuBtn.setOnClickListener {
            when (TaskSource.shizukuState()) {
                1 -> try { Shizuku.requestPermission(77) } catch (t: Throwable) {}
                0 -> {
                    val i = try { packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") } catch (e: Exception) { null }
                    if (i != null) startActivity(i)
                    else Toast.makeText(this, "Install and start the Shizuku app first", Toast.LENGTH_LONG).show()
                }
            }
        }
        openCard.addView(shizukuBtn)
        openCard.addView(tv("Shizuku is optional. Without it the list is an estimate (apps used in the last 2 hours). With it, the list shows the real open apps.", 12f, false, Color.GRAY))

        val bubbleCard = card(root, "Floating bubble")
        val sw = Switch(this)
        sw.text = "Show Sync bubble on screen"
        sw.isChecked = getSharedPreferences("c", 0).getBoolean("bubble", false)
        sw.setOnCheckedChangeListener { _, on ->
            getSharedPreferences("c", 0).edit().putBoolean("bubble", on).apply()
            if (on && !canOverlay()) {
                Toast.makeText(this, "Allow draw over apps first", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            if (SyncService.on) startSvc()
        }
        bubbleCard.addView(sw)

        val tbCard = card(root, "Taskbar button")
        val tbSw = Switch(this)
        tbSw.text = "Show Sync button in Taskbar"
        tbSw.isChecked = getSharedPreferences("c", 0).getBoolean("tbbutton", true)
        tbSw.setOnCheckedChangeListener { _, on ->
            getSharedPreferences("c", 0).edit().putBoolean("tbbutton", on).apply()
            Toast.makeText(this, "Restart Taskbar to apply", Toast.LENGTH_SHORT).show()
        }
        tbCard.addView(tbSw)
        tbCard.addView(tv("Only needed if you use the Taskbar app. Restart Taskbar after changing.", 12f, false, Color.GRAY))

        val pinCard = card(root, "My PIN")
        val pinView = tv(Net.myPin(this), 40f, true, 0xFF1976D2.toInt())
        pinView.letterSpacing = 0.2f
        pinCard.addView(pinView)
        pinCard.addView(tv("Enter this PIN on the other device to pair", 12f, false, Color.GRAY))

        val pairCard = card(root, "Pair a device")
        val et = EditText(this)
        et.hint = "PIN of the other device"
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.filters = arrayOf(InputFilter.LengthFilter(4))
        pairCard.addView(et)
        val bp = Button(this)
        bp.text = "Pair"
        bp.setOnClickListener {
            val p = et.text.toString()
            if (p.length != 4) {
                Toast.makeText(this, "Enter 4-digit PIN", Toast.LENGTH_SHORT).show()
            } else if (p == Net.myPin(this)) {
                Toast.makeText(this, "That is this device's PIN", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Pairing... keep the other app open", Toast.LENGTH_LONG).show()
                et.setText("")
                val msg = "P|${Net.myId(this)}|${Net.myName()}|${Net.myPin(this)}"
                thread {
                    repeat(3) {
                        Net.sendTo(p, msg)
                        Thread.sleep(1000)
                    }
                }
            }
        }
        pairCard.addView(bp)

        peerBox = card(root, "Paired devices")

        val tile = tv("Messages  \u203A", 16f, true)
        tile.background = bg(Color.WHITE, 12)
        tile.setPadding(dp(16), dp(18), dp(16), dp(18))
        tile.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.topMargin = dp(12)
        root.addView(tile, tlp)

        svcBtn = Button(this)
        svcBtn.setOnClickListener {
            val prefs = getSharedPreferences("c", 0)
            if (SyncService.on) {
                prefs.edit().putBoolean("stopped", true).apply()
                stopService(Intent(this, SyncService::class.java))
            } else {
                prefs.edit().putBoolean("stopped", false).apply()
                startSvc()
            }
            h.postDelayed({ refresh() }, 700)
        }
        root.addView(svcBtn)
        root.addView(tv("Build v7 (direct connection + pieces)", 11f, false, Color.GRAY))

        setContentView(scroll)
    }

    private fun startSvc() {
        val i = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (t: Throwable) {}
        if (!getSharedPreferences("c", 0).getBoolean("stopped", false)) startSvc()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (t: Throwable) {}
        h.removeCallbacks(tick)
    }

    private fun permRow(label: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(label, 15f))
        col.addView(
            tv(
                if (granted) "Allowed" else "Not allowed", 12f, false,
                if (granted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
        )
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!granted) {
            val bt = Button(this)
            bt.text = "Allow"
            bt.setOnClickListener { onGrant() }
            row.addView(bt)
        }
        permBox.addView(row)
    }

    private fun refresh() {
        permBox.removeViews(1, permBox.childCount - 1)
        permRow("Draw over apps (only for the floating bubble)", canOverlay()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permRow("Notifications", notif) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        peerBox.removeViews(1, peerBox.childCount - 1)
        val peers = Peers.list(this)
        if (peers.isEmpty()) {
            peerBox.addView(tv("No paired device", 13f, false, Color.GRAY))
        }
        for (p in peers) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(p.name, 15f, true))
            val on = Peers.online(p.id)
            col.addView(
                tv(
                    if (on) "Connected" else "Not connected", 13f, false,
                    if (on) 0xFF2E7D32.toInt() else Color.GRAY
                )
            )
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val bu = Button(this)
            bu.text = "Unpair"
            bu.setOnClickListener {
                Peers.remove(this, p.id)
                refresh()
            }
            row.addView(bu)
            peerBox.addView(row)
        }

        when (TaskSource.shizukuState()) {
            2 -> {
                shizukuStatus.text = "Shizuku: connected (real open apps)"
                shizukuBtn.visibility = android.view.View.GONE
            }
            1 -> {
                shizukuStatus.text = "Shizuku: running, permission needed"
                shizukuBtn.text = "Allow Shizuku"
                shizukuBtn.visibility = android.view.View.VISIBLE
            }
            else -> {
                shizukuStatus.text = "Shizuku: not running (using estimate)"
                shizukuBtn.text = "Open Shizuku"
                shizukuBtn.visibility = android.view.View.VISIBLE
            }
        }

        svcBtn.text = if (SyncService.on) "Stop service" else "Start service"
    }
}
