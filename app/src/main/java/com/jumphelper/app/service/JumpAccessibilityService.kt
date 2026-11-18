package com.jumphelper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.jumphelper.app.core.JumpControllerState
import com.jumphelper.app.core.JumpDetector
import com.jumphelper.app.core.JumpPreferences
import com.jumphelper.app.core.RunMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class JumpAccessibilityService : AccessibilityService(), FloatingOverlayManager.OverlayActionListener {

    private lateinit var prefs: JumpPreferences
    private lateinit var overlayManager: FloatingOverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var isCycleBusy = false
    private var screenshotRetryCount = 0

    private val scheduledAutoStep = Runnable {
        if (JumpControllerState.runMode.value == RunMode.AUTO_RUNNING && !isCycleBusy) {
            executeScreenshotDetectAndJump(isAutoLoop = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = JumpPreferences(this)
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        JumpControllerState.setServiceConnected(true)

        overlayManager = FloatingOverlayManager(this, prefs, this)
        overlayManager.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen-capture & gesture service does not inspect UI tree events.
    }

    override fun onInterrupt() {
        pauseAll("服务被系统中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        workerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        isCycleBusy = false
        if (::overlayManager.isInitialized) {
            overlayManager.detach()
        }
        JumpControllerState.setServiceConnected(false)
        if (instance === this) {
            instance = null
        }
    }

    fun refreshOverlayFromPrefs() {
        if (!::overlayManager.isInitialized) return
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        overlayManager.applyVisibilityFromPrefs()
        overlayManager.refreshUi()
    }

    fun resetOverlayPosition() {
        if (!::overlayManager.isInitialized) return
        prefs.showOverlay = true
        overlayManager.applyVisibilityFromPrefs()
        overlayManager.resetPosition()
    }

    override fun onAutoToggleRequested() {
        if (JumpControllerState.runMode.value == RunMode.AUTO_RUNNING) {
            pauseAll("已暂停")
        } else {
            startAutoLoop()
        }
    }

    override fun onSingleStepRequested() {
        if (isCycleBusy) return
        mainHandler.removeCallbacks(scheduledAutoStep)
        JumpControllerState.setRunMode(RunMode.PAUSED)
        JumpControllerState.updateStatus("单步识别中")
        overlayManager.refreshUi()
        executeScreenshotDetectAndJump(isAutoLoop = false)
    }

    override fun onManualAimRequested() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        isCycleBusy = false
        JumpControllerState.setRunMode(RunMode.MANUAL_AIMING)
        JumpControllerState.updateStatus("双点瞄准")
        overlayManager.refreshUi()

        overlayManager.showManualAimOverlay(
            onPointsConfirmed = { x1, y1, x2, y2 ->
                val bounds = overlayManager.getDisplayBounds()
                val pressMs = JumpDetector.calculateManualPressTimeMs(
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    screenWidth = bounds.width(),
                    screenHeight = bounds.height(),
                    jumpFullWidthMs = prefs.jumpFullWidthMs
                )
                val distPx = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                isCycleBusy = true
                JumpControllerState.setRunMode(RunMode.PAUSED)
                JumpControllerState.recordJump(distPx, pressMs, "手动跳跃")
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(x1, y1, x2, y2, pressMs)

                mainHandler.postDelayed({
                    dispatchRandomizedLongPress(pressMs, scheduleNextAuto = false)
                }, 70L)
            },
            onCancelled = {
                JumpControllerState.setRunMode(RunMode.PAUSED)
                JumpControllerState.updateStatus("已取消瞄准")
                overlayManager.refreshUi()
            }
        )
    }

    override fun onCoefficientAdjusted(newCoeffMs: Int) {
        // State and prefs are already updated by FloatingOverlayManager
    }

    private fun startAutoLoop() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        screenshotRetryCount = 0
        JumpControllerState.setRunMode(RunMode.AUTO_RUNNING)
        JumpControllerState.updateStatus("自动连跳中")
        overlayManager.refreshUi()
        if (!isCycleBusy) {
            executeScreenshotDetectAndJump(isAutoLoop = true)
        }
    }

    private fun pauseAll(statusText: String) {
        mainHandler.removeCallbacks(scheduledAutoStep)
        isCycleBusy = false
        JumpControllerState.setRunMode(RunMode.PAUSED)
        JumpControllerState.updateStatus(statusText)
        if (::overlayManager.isInitialized) {
            overlayManager.removeManualAimOverlay()
            overlayManager.setHiddenForCapture(false)
            overlayManager.refreshUi()
        }
    }

    private fun executeScreenshotDetectAndJump(isAutoLoop: Boolean) {
        if (isCycleBusy) return
        isCycleBusy = true

        // Hide floating overlay briefly so SurfaceFlinger captures pure game pixels
        overlayManager.setHiddenForCapture(true)
        mainHandler.postDelayed({
            if (isAutoLoop && JumpControllerState.runMode.value != RunMode.AUTO_RUNNING) {
                overlayManager.setHiddenForCapture(false)
                isCycleBusy = false
                return@postDelayed
            }

            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    workerExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            mainHandler.post {
                                overlayManager.setHiddenForCapture(false)
                            }
                            val outcome = BitmapPixelExtractor.analyzeScreenshot(
                                screenshotResult = screenshot,
                                jumpFullWidthMs = prefs.jumpFullWidthMs
                            )
                            mainHandler.post {
                                handleDetectionOutcome(outcome, isAutoLoop)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            mainHandler.post {
                                overlayManager.setHiddenForCapture(false)
                                handleScreenshotFailure(errorCode, isAutoLoop)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                overlayManager.setHiddenForCapture(false)
                isCycleBusy = false
                pauseAll("截图调用异常")
            }
        }, OVERLAY_HIDE_SETTLE_MS)
    }

    private fun handleScreenshotFailure(errorCode: Int, isAutoLoop: Boolean) {
        isCycleBusy = false
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
            isAutoLoop &&
            JumpControllerState.runMode.value == RunMode.AUTO_RUNNING &&
            screenshotRetryCount < 2
        ) {
            screenshotRetryCount++
            JumpControllerState.updateStatus("截图冷却中，稍后重试")
            overlayManager.refreshUi()
            mainHandler.postDelayed(scheduledAutoStep, 650L)
            return
        }

        val reason = when (errorCode) {
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图过频，请调大间隔"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无截图权限，请重启服务"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "屏幕显示无效"
            else -> "截图失败($errorCode)"
        }
        pauseAll(reason)
    }

    private fun handleDetectionOutcome(
        outcome: JumpDetector.DetectionOutcome,
        isAutoLoop: Boolean
    ) {
        if (isAutoLoop && JumpControllerState.runMode.value != RunMode.AUTO_RUNNING) {
            isCycleBusy = false
            return
        }

        when (outcome) {
            is JumpDetector.DetectionOutcome.Failure -> {
                isCycleBusy = false
                pauseAll(outcome.reason)
            }
            is JumpDetector.DetectionOutcome.Success -> {
                screenshotRetryCount = 0
                val res = outcome.result
                val statusLabel = if (isAutoLoop) "连跳中" else "单步完成"
                JumpControllerState.recordJump(res.distanceX, res.pressTimeMs, statusLabel)
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(
                    pieceX = res.pieceX,
                    pieceY = res.pieceY,
                    boardX = res.boardX,
                    boardY = res.boardY,
                    pressMs = res.pressTimeMs
                )
                dispatchRandomizedLongPress(res.pressTimeMs, scheduleNextAuto = isAutoLoop)
            }
        }
    }

    private fun dispatchRandomizedLongPress(pressTimeMs: Long, scheduleNextAuto: Boolean) {
        val bounds = overlayManager.getDisplayBounds()
        val w = bounds.width().coerceAtLeast(360)
        val h = bounds.height().coerceAtLeast(640)
        val barRect = overlayManager.getControlBarRect()

        val (startX, startY) = pickSafePressCoordinate(w, h, barRect)
        val driftX = Random.nextFloat() * 14f - 7f
        val driftY = Random.nextFloat() * 14f - 7f
        val endX = (startX + driftX).coerceIn(20f, (w - 20).toFloat())
        val endY = (startY + driftY).coerceIn(20f, (h - 20).toFloat())

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, pressTimeMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isCycleBusy = false
                    if (scheduleNextAuto && JumpControllerState.runMode.value == RunMode.AUTO_RUNNING) {
                        val minWait = prefs.minIntervalMs
                        val maxWait = prefs.maxIntervalMs
                        val nextWait = Random.nextInt(minWait, maxWait + 1).toLong()
                        mainHandler.postDelayed(scheduledAutoStep, nextWait)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isCycleBusy = false
                    if (scheduleNextAuto && JumpControllerState.runMode.value == RunMode.AUTO_RUNNING) {
                        pauseAll("手势被中断，已暂停")
                    }
                }
            },
            mainHandler
        )

        if (!dispatched) {
            isCycleBusy = false
            pauseAll("手势派发失败")
        }
    }

    private fun pickSafePressCoordinate(w: Int, h: Int, barRect: Rect): Pair<Float, Float> {
        val minX = w * 0.24f
        val maxX = w * 0.76f
        val minY = h * 0.68f
        val maxY = h * 0.83f
        val paddedRect = Rect(barRect.left - 24, barRect.top - 24, barRect.right + 24, barRect.bottom + 24)

        repeat(10) {
            val rx = minX + Random.nextFloat() * (maxX - minX)
            val ry = minY + Random.nextFloat() * (maxY - minY)
            if (paddedRect.isEmpty || !paddedRect.contains(rx.toInt(), ry.toInt())) {
                return rx to ry
            }
        }
        // Fallback outside bar if control bar sits directly inside bottom area
        val fallbackY = if (barRect.centerY() > h * 0.72f) h * 0.56f else h * 0.80f
        return (w * 0.5f) to fallbackY
    }

    companion object {
        private const val OVERLAY_HIDE_SETTLE_MS = 75L

        @Volatile
        var instance: JumpAccessibilityService? = null
            private set
    }
}
