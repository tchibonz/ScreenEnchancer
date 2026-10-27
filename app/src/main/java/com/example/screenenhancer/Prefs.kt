package com.example.screenenhancer

import android.content.Context

/** Process-wide settings. The GL renderer reads these every frame. */
object Prefs {
    @Volatile var sharpness = 0.6f   // 0 = off, 1 = strongest
    @Volatile var contrast = 1.05f   // 1 = neutral
    @Volatile var saturation = 1.10f // 1 = neutral
    @Volatile var wipe = 0f          // 0 = off, else fraction of the screen (from the left) left unprocessed
    @Volatile var denoise = 0.35f    // 0 = off, 1 = strongest noise / compression-artifact cleaning
    @Volatile var enabled = true     // master switch for all enhancement
    @Volatile var aiMode = 0         // 0 auto, 1 off, 2 small model, 3 full model
    @Volatile var opacity = 0.5f    // overlay window alpha; Android 12+ only passes touches through at <= 0.8
    @Volatile var testMode = true   // auto-stop after 20 s so a bad capture can never lock the phone

    private const val FILE = "screen_enhancer"

    fun load(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sharpness = p.getFloat("sharpness", sharpness)
        contrast = p.getFloat("contrast", contrast)
        saturation = p.getFloat("saturation", saturation)
        wipe = p.getFloat("wipe", wipe)
        testMode = p.getBoolean("testMode", testMode)
        opacity = p.getFloat("opacity", opacity)
        aiMode = p.getInt("aiMode", aiMode)
        enabled = p.getBoolean("enabled", enabled)
        denoise = p.getFloat("denoise", denoise)
    }

    fun save(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("sharpness", sharpness)
            .putFloat("contrast", contrast)
            .putFloat("saturation", saturation)
            .putFloat("wipe", wipe)
            .putBoolean("testMode", testMode)
            .putFloat("opacity", opacity)
            .putInt("aiMode", aiMode)
            .putBoolean("enabled", enabled)
            .putFloat("denoise", denoise)
            .apply()
    }
}
