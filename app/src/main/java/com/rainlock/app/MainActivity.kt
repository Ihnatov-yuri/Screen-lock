package com.rainlock.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rainlock.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var selectedKey: UnlockKey = UnlockKey.EITHER
    private var dimOn: Boolean = false

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        wire()
        loadIntoUi()
    }

    // SeekBar ranges: count 2..6 (max=4), window 5..35 in 100ms steps (max=30 → 0.5s..3.5s)
    private fun countFromSeek(): Int = b.countSeek.progress + 2
    private fun windowMsFromSeek(): Long = ((b.windowSeek.progress + 5) * 100).toLong()

    private fun wire() {
        b.rowVolUp.setOnClickListener { setKey(UnlockKey.VOL_UP) }
        b.rowVolDown.setOnClickListener { setKey(UnlockKey.VOL_DOWN) }
        b.rowEither.setOnClickListener { setKey(UnlockKey.EITHER) }
        b.rowDim.setOnClickListener { setDim(!dimOn) }

        val redraw = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                renderNumbers()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        b.countSeek.max = 4
        b.windowSeek.max = 30
        b.countSeek.setOnSeekBarChangeListener(redraw)
        b.windowSeek.setOnSeekBarChangeListener(redraw)

        b.startBlock.setOnClickListener { onStartClicked() }

        b.aboutLink.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ihnatov.nl")))
            }
        }
    }

    private fun loadIntoUi() {
        val cfg = Settings.load(this)
        selectedKey = cfg.key
        dimOn = cfg.dimOverlay
        b.countSeek.progress = (cfg.pressCount - 2).coerceIn(0, b.countSeek.max)
        b.windowSeek.progress = ((cfg.windowMs / 100) - 5).toInt().coerceIn(0, b.windowSeek.max)
        renderKey()
        renderDim()
        renderNumbers()
    }

    private fun setKey(k: UnlockKey) { selectedKey = k; renderKey() }
    private fun setDim(on: Boolean) { dimOn = on; renderDim() }

    private fun renderKey() {
        // Only one orange dot visible at a time across the three rows — the "punctuation" accent.
        b.dotVolUp.visibility = if (selectedKey == UnlockKey.VOL_UP) View.VISIBLE else View.INVISIBLE
        b.dotVolDown.visibility = if (selectedKey == UnlockKey.VOL_DOWN) View.VISIBLE else View.INVISIBLE
        b.dotEither.visibility = if (selectedKey == UnlockKey.EITHER) View.VISIBLE else View.INVISIBLE
    }

    private fun renderDim() {
        b.dimState.text = if (dimOn) "ON" else "OFF"
        b.dotDim.visibility = if (dimOn) View.VISIBLE else View.INVISIBLE
        val color = ContextCompat.getColor(this, if (dimOn) R.color.ink else R.color.mono)
        b.dimState.setTextColor(color)
    }

    private fun renderNumbers() {
        b.countNumber.text = countFromSeek().toString()
        // window: render as e.g. "2.0" — tabular figures via fontFeatureSettings handle alignment
        val secs = windowMsFromSeek() / 1000.0
        b.windowNumber.text = String.format("%.1f", secs)
    }

    private fun saveFromUi(): UnlockConfig {
        val cfg = UnlockConfig(
            key = selectedKey,
            pressCount = countFromSeek(),
            windowMs = windowMsFromSeek(),
            dimOverlay = dimOn,
        )
        Settings.save(this, cfg)
        return cfg
    }

    private fun onStartClicked() {
        saveFromUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!SysSettings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage("RainLock needs 'Display over other apps' to draw the touch-blocking overlay.")
                .setPositiveButton("Open settings") { _, _ ->
                    val i = Intent(
                        SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(i)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Lock in 3 seconds")
            .setMessage("Switch to your navigation app now. Overlay will appear on top.")
            .setPositiveButton("Go") { _, _ ->
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(home)
                Handler(Looper.getMainLooper()).postDelayed({
                    val svc = Intent(this, LockOverlayService::class.java)
                        .setAction(LockOverlayService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc)
                    } else {
                        startService(svc)
                    }
                }, 3000)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
