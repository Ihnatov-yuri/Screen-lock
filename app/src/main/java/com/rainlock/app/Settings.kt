package com.rainlock.app

import android.content.Context

enum class UnlockKey { VOL_UP, VOL_DOWN, EITHER }

data class UnlockConfig(
    val key: UnlockKey,
    val pressCount: Int,
    val windowMs: Long,
    val dimOverlay: Boolean,
)

object Settings {
    private const val PREFS = "rainlock_prefs"
    private const val KEY_UNLOCK_KEY = "unlock_key"
    private const val KEY_PRESS_COUNT = "press_count"
    private const val KEY_WINDOW_MS = "window_ms"
    private const val KEY_DIM = "dim_overlay"

    fun load(ctx: Context): UnlockConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UnlockConfig(
            key = runCatching {
                UnlockKey.valueOf(p.getString(KEY_UNLOCK_KEY, UnlockKey.EITHER.name)!!)
            }.getOrDefault(UnlockKey.EITHER),
            pressCount = p.getInt(KEY_PRESS_COUNT, 3),
            windowMs = p.getLong(KEY_WINDOW_MS, 2000L),
            dimOverlay = p.getBoolean(KEY_DIM, false),
        )
    }

    fun save(ctx: Context, cfg: UnlockConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UNLOCK_KEY, cfg.key.name)
            .putInt(KEY_PRESS_COUNT, cfg.pressCount)
            .putLong(KEY_WINDOW_MS, cfg.windowMs)
            .putBoolean(KEY_DIM, cfg.dimOverlay)
            .apply()
    }
}
