package com.rainlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat

class LockOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.rainlock.app.START"
        const val ACTION_STOP = "com.rainlock.app.STOP"
        private const val CHANNEL_ID = "rainlock_overlay"
        private const val NOTIF_ID = 1
    }

    private var overlay: View? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val pressTimestamps = ArrayDeque<Long>()
    private var lastKey: UnlockKey? = null

    private lateinit var cfg: UnlockConfig

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelfClean(); return START_NOT_STICKY }
            else -> startLock()
        }
        return START_STICKY
    }

    private fun startLock() {
        cfg = Settings.load(this)
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
        startMediaSession()
        acquireWakeLock()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "RainLock overlay",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val stopIntent = Intent(this, LockOverlayService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RainLock active")
            .setContentText("Touch is blocked. Use unlock combo.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Unlock", stopPi).build())
            .build()
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(if (cfg.dimOverlay) Color.argb(80, 0, 0, 0) else Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, _: MotionEvent -> true }
        }

        // Small red indicator dot in the top-right so the user knows the lock is armed.
        val indicator = View(this).apply {
            background = ContextCompat.getDrawable(this@LockOverlayService, R.drawable.lock_indicator)
        }
        val sizePx = dp(20)
        val marginPx = dp(12)
        val lp = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = marginPx + statusBarHeight()
            rightMargin = marginPx
        }
        container.addView(indicator, lp)

        wm.addView(container, params)
        overlay = container
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun startMediaSession() {
        val session = MediaSessionCompat(this, "RainLockSession")
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
        val provider = object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE, 100, 50
        ) {
            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    AudioManager.ADJUST_RAISE -> recordPress(UnlockKey.VOL_UP)
                    AudioManager.ADJUST_LOWER -> recordPress(UnlockKey.VOL_DOWN)
                }
            }
        }
        session.setPlaybackToRemote(provider)
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
            .build()
        session.setPlaybackState(state)
        session.isActive = true
        mediaSession = session
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RainLock:keepCpu"
        )
        wl.setReferenceCounted(false)
        wl.acquire(/* 12 hours */ 12L * 60L * 60L * 1000L)
        wakeLock = wl
    }

    private fun recordPress(key: UnlockKey) {
        val now = System.currentTimeMillis()
        val matches = when (cfg.key) {
            UnlockKey.EITHER -> true
            UnlockKey.VOL_UP -> key == UnlockKey.VOL_UP
            UnlockKey.VOL_DOWN -> key == UnlockKey.VOL_DOWN
        }
        if (!matches) {
            pressTimestamps.clear()
            lastKey = null
            return
        }
        // EITHER mode: all presses must be the same key within window
        if (cfg.key == UnlockKey.EITHER) {
            if (lastKey != null && lastKey != key) {
                pressTimestamps.clear()
            }
            lastKey = key
        }
        pressTimestamps.addLast(now)
        while (pressTimestamps.isNotEmpty() && now - pressTimestamps.first() > cfg.windowMs) {
            pressTimestamps.removeFirst()
        }
        if (pressTimestamps.size >= cfg.pressCount) {
            stopSelfClean()
        }
    }

    private fun stopSelfClean() {
        runCatching {
            overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        overlay = null
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfClean()
        super.onDestroy()
    }
}
