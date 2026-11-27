package com.jumphelper.app.core

import android.content.Context
import android.content.SharedPreferences

class JumpPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var jumpFullWidthMs: Int
        get() = prefs.getInt(KEY_FULL_WIDTH_MS, JumpDetector.RECOMMENDED_INITIAL_COEFF_MS)
            .coerceIn(MIN_COEFF_MS, MAX_COEFF_MS)
        set(value) {
            prefs.edit().putInt(KEY_FULL_WIDTH_MS, value.coerceIn(MIN_COEFF_MS, MAX_COEFF_MS)).apply()
        }

    var pressOffsetMs: Int
        get() = prefs.getInt(KEY_PRESS_OFFSET_MS, DEFAULT_PRESS_OFFSET_MS)
            .coerceIn(JumpDetector.MIN_PRESS_OFFSET_MS, JumpDetector.MAX_PRESS_OFFSET_MS)
        set(value) {
            prefs.edit().putInt(
                KEY_PRESS_OFFSET_MS,
                value.coerceIn(JumpDetector.MIN_PRESS_OFFSET_MS, JumpDetector.MAX_PRESS_OFFSET_MS)
            ).apply()
        }

    var usePhysicsCurve: Boolean
        get() = prefs.getBoolean(KEY_PHYSICS_CURVE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PHYSICS_CURVE, value).apply()
        }

    var autoCalibrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALIBRATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CALIBRATE, value).apply()
        }

    var smartEscortEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_ESCORT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SMART_ESCORT, value).apply()
        }

    var calibrationSamples: Int
        get() = prefs.getInt(KEY_CALIBRATION_SAMPLES, 0).coerceAtLeast(0)
        set(value) {
            prefs.edit().putInt(KEY_CALIBRATION_SAMPLES, value.coerceAtLeast(0)).apply()
        }

    var minIntervalMs: Int
        get() = prefs.getInt(KEY_MIN_INTERVAL_MS, DEFAULT_MIN_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_FLOOR_MS, MAX_INTERVAL_CEIL_MS)
        set(value) {
            prefs.edit().putInt(
                KEY_MIN_INTERVAL_MS,
                value.coerceIn(MIN_INTERVAL_FLOOR_MS, MAX_INTERVAL_CEIL_MS)
            ).apply()
        }

    val maxIntervalMs: Int
        get() = minIntervalMs + INTERVAL_JITTER_MS

    var showDebugMarker: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MARKER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MARKER, value).apply()
        }

    var showOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OVERLAY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_OVERLAY, value).apply()
        }

    var foregroundKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_FOREGROUND_KEEP_ALIVE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FOREGROUND_KEEP_ALIVE, value).apply()
        }

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_X, value).apply()
        }

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()
        }

    fun resetCalibration() {
        prefs.edit()
            .putInt(KEY_FULL_WIDTH_MS, JumpDetector.RECOMMENDED_INITIAL_COEFF_MS)
            .putInt(KEY_PRESS_OFFSET_MS, DEFAULT_PRESS_OFFSET_MS)
            .putInt(KEY_CALIBRATION_SAMPLES, 0)
            .apply()
    }

    fun resetOverlayPosition() {
        prefs.edit()
            .remove(KEY_OVERLAY_X)
            .remove(KEY_OVERLAY_Y)
            .apply()
    }

    companion object {
        const val MIN_COEFF_MS = 1000
        const val MAX_COEFF_MS = 2800
        const val DEFAULT_PRESS_OFFSET_MS = 0

        const val TEMPO_TURBO_MS = 1050
        const val TEMPO_BALANCED_MS = 1450
        const val TEMPO_STEADY_MS = 2050

        const val DEFAULT_MIN_INTERVAL_MS = TEMPO_BALANCED_MS
        const val MIN_INTERVAL_FLOOR_MS = 950
        const val MAX_INTERVAL_CEIL_MS = 3600
        const val INTERVAL_JITTER_MS = 400

        private const val PREFS_NAME = "jump_helper_prefs"
        private const val KEY_FULL_WIDTH_MS = "full_width_ms"
        private const val KEY_PRESS_OFFSET_MS = "press_offset_ms"
        private const val KEY_PHYSICS_CURVE = "physics_curve"
        private const val KEY_AUTO_CALIBRATE = "auto_calibrate"
        private const val KEY_SMART_ESCORT = "smart_escort"
        private const val KEY_CALIBRATION_SAMPLES = "calibration_samples"
        private const val KEY_MIN_INTERVAL_MS = "min_interval_ms"
        private const val KEY_DEBUG_MARKER = "debug_marker"
        private const val KEY_SHOW_OVERLAY = "show_overlay"
        private const val KEY_FOREGROUND_KEEP_ALIVE = "foreground_keep_alive"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
    }
}
