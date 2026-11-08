package com.example.mirrorsender

import android.content.Context
import android.media.AudioManager

/** Silences the phone's own media volume while mirroring and restores it afterwards. */
object AudioMute {
    private const val PREFS = "cfg"
    private const val K = "saved_music_volume"

    fun mute(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (!prefs.contains(K) && cur > 0) prefs.edit().putInt(K, cur).commit()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) {
        }
    }

    fun restore(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(K)) return
            val am = ctx.getSystemService(AudioManager::class.java)
            // only put it back if it is still silent (the user may have raised it themselves)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(K, 5), 0)
            }
            prefs.edit().remove(K).commit()
        } catch (_: Exception) {
        }
    }
}
