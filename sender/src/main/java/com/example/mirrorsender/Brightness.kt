package com.example.mirrorsender

import android.content.Context
import android.provider.Settings

/** Dims the phone screen to the minimum while mirroring and puts the user's setting back afterwards. */
object Brightness {
    private const val PREFS = "cfg"
    private const val K_B = "saved_brightness"
    private const val K_M = "saved_mode"

    fun dim(ctx: Context) {
        try {
            if (!Settings.System.canWrite(ctx)) return
            val cr = ctx.contentResolver
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(K_B)) {   // remember the ORIGINAL setting only once
                prefs.edit()
                    .putInt(K_B, Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                    .putInt(
                        K_M,
                        Settings.System.getInt(
                            cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                    )
                    .commit()
            }
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 1)
        } catch (_: Exception) {
        }
    }

    fun restore(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(K_B)) return
            if (Settings.System.canWrite(ctx)) {
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt(K_B, 128))
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    prefs.getInt(K_M, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                )
            }
            prefs.edit().remove(K_B).remove(K_M).commit()
        } catch (_: Exception) {
        }
    }
}
