package org.capture.a9.util

import android.os.SystemClock

class FpsMeter(private val updateIntervalMs: Long = 500L) {
    private var frameCount = 0
    private var lastUpdateTime = SystemClock.elapsedRealtime()
    private var currentFps = 0.0f

    fun onFrame(): Float {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastUpdateTime
        if (elapsed >= updateIntervalMs) {
            currentFps = (frameCount * 1000.0f) / elapsed
            frameCount = 0
            lastUpdateTime = now
        }
        return currentFps
    }

    fun getFps(): Float = currentFps
}
