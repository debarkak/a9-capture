package org.capture.a9.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("a9_capture_prefs", Context.MODE_PRIVATE)

    companion object {
        const val SCALE_MODE_STRETCH = 0 // 16:10 Full A9+
        const val SCALE_MODE_FIT = 1     // 16:9 Letterbox
        const val SCALE_MODE_FILL = 2    // Crop fill
        private const val KEY_SCALE_MODE = "scale_mode"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_TARGET_FPS = "target_fps"
    }

    var scaleMode: Int
        get() = prefs.getInt(KEY_SCALE_MODE, SCALE_MODE_STRETCH)
        set(value) = prefs.edit().putInt(KEY_SCALE_MODE, value).apply()

    var isAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply()

    var targetFps: Int
        get() = prefs.getInt(KEY_TARGET_FPS, 90)
        set(value) = prefs.edit().putInt(KEY_TARGET_FPS, value).apply()
}
