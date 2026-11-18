package com.jumphelper.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunMode {
    IDLE,
    AUTO_RUNNING,
    PAUSED,
    MANUAL_AIMING
}

object JumpControllerState {

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _runMode = MutableStateFlow(RunMode.IDLE)
    val runMode: StateFlow<RunMode> = _runMode.asStateFlow()

    private val _jumpCount = MutableStateFlow(0)
    val jumpCount: StateFlow<Int> = _jumpCount.asStateFlow()

    private val _lastStatusText = MutableStateFlow("待命")
    val lastStatusText: StateFlow<String> = _lastStatusText.asStateFlow()

    private val _lastDistancePx = MutableStateFlow(0f)
    val lastDistancePx: StateFlow<Float> = _lastDistancePx.asStateFlow()

    private val _lastPressMs = MutableStateFlow(0L)
    val lastPressMs: StateFlow<Long> = _lastPressMs.asStateFlow()

    private val _lastScreenshotSize = MutableStateFlow("")
    val lastScreenshotSize: StateFlow<String> = _lastScreenshotSize.asStateFlow()

    private val _jumpFullWidthMs = MutableStateFlow(JumpDetector.DEFAULT_JUMP_FULL_WIDTH_MS)
    val jumpFullWidthMs: StateFlow<Int> = _jumpFullWidthMs.asStateFlow()

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
        if (!connected) {
            _runMode.value = RunMode.IDLE
            _lastStatusText.value = "无障碍服务未开启"
        } else {
            _lastStatusText.value = "待命"
        }
    }

    fun setRunMode(mode: RunMode) {
        _runMode.value = mode
    }

    fun setJumpFullWidthMs(value: Int) {
        _jumpFullWidthMs.value = value.coerceIn(
            JumpPreferences.MIN_COEFF_MS,
            JumpPreferences.MAX_COEFF_MS
        )
    }

    fun setScreenshotSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _lastScreenshotSize.value = "${width}×$height"
        }
    }

    fun updateStatus(text: String) {
        _lastStatusText.value = text
    }

    fun recordJump(distancePx: Float, pressMs: Long, status: String) {
        _jumpCount.value = _jumpCount.value + 1
        _lastDistancePx.value = distancePx
        _lastPressMs.value = pressMs
        _lastStatusText.value = status
    }

    fun resetSessionStats() {
        _jumpCount.value = 0
        _lastDistancePx.value = 0f
        _lastPressMs.value = 0L
        _lastScreenshotSize.value = ""
    }
}
