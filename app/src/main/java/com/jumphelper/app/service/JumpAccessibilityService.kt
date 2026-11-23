package com.jumphelper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.jumphelper.app.R
import com.jumphelper.app.core.JumpControllerState
import com.jumphelper.app.core.JumpDetector
import com.jumphelper.app.core.JumpPreferences
import com.jumphelper.app.core.ProcessDiagnostics
import com.jumphelper.app.core.RunMode
import com.jumphelper.app.ui.MainActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class JumpAccessibilityService : AccessibilityService(), FloatingOverlayManager.OverlayActionListener {

    private lateinit var prefs: JumpPreferences
    private lateinit var overlayManager: FloatingOverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var isCycleBusy = false
    private var isInForeground = false
    private var screenshotRetryCount = 0
    private var activeCaptureToken = 0L
    private var activeGestureToken = 0L

    private var captureTimeoutRunnable: Runnable? = null
    private var gestureWatchdogRunnable: Runnable? = null

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

        if (::overlayManager.isInitialized) {
            overlayManager.detach()
        }
        overlayManager = FloatingOverlayManager(this, prefs, this)
        overlayManager.attach()

        ensureForegroundKeepAlive("悬浮控制条已就绪")
        ProcessDiagnostics.recordEvent(this, "服务已连接(foreground=$isInForeground)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen-capture & gesture service does not inspect UI tree events.
    }

    override fun onInterrupt() {
        ProcessDiagnostics.recordEvent(this, "收到系统 onInterrupt")
        pauseAll("服务被系统中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ProcessDiagnostics.recordEvent(this, "服务被解绑 onUnbind")
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ProcessDiagnostics.recordEvent(this, "服务销毁 onDestroy")
        cleanup()
        workerExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BitmapPixelExtractor.clearCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BitmapPixelExtractor.clearCache()
    }

    private fun cleanup() {
        activeCaptureToken++
        activeGestureToken++
        mainHandler.removeCallbacksAndMessages(null)
        captureTimeoutRunnable = null
        gestureWatchdogRunnable = null
        isCycleBusy = false
        BitmapPixelExtractor.clearCache()
        stopForegroundKeepAlive()
        if (::overlayManager.isInitialized) {
            overlayManager.detach()
        }
        JumpControllerState.setServiceConnected(false)
        if (instance === this) {
            instance = null
        }
    }

    fun ensureForegroundKeepAlive(statusDetail: String? = null) {
        if (!::prefs.isInitialized) return
        if (!prefs.foregroundKeepAlive) {
            stopForegroundKeepAlive()
            return
        }
        val detail = statusDetail ?: buildNotificationText()
        val notification = buildKeepAliveNotification(detail)
        if (isInForeground) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(KEEP_ALIVE_NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
            return
        }
        try {
            ensureNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    KEEP_ALIVE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification)
            }
            isInForeground = true
            JumpControllerState.setForegroundActive(true)
        } catch (t: Throwable) {
            isInForeground = false
            JumpControllerState.setForegroundActive(false)
            ProcessDiagnostics.recordEvent(this, "前台保活启动受限: ${t.javaClass.simpleName}")
        }
    }

    private fun stopForegroundKeepAlive() {
        if (!isInForeground) {
            JumpControllerState.setForegroundActive(false)
            return
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        } finally {
            isInForeground = false
            JumpControllerState.setForegroundActive(false)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(KEEP_ALIVE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            KEEP_ALIVE_CHANNEL_ID,
            "跳一跳助手前台保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持无障碍悬浮控制条与自动连跳在后台运行不被系统清理"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildKeepAliveNotification(contentText: String): Notification {
        ensureNotificationChannel()
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, KEEP_ALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jump)
            .setContentTitle("跳一跳助手运行中")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildNotificationText(): String {
        val count = JumpControllerState.jumpCount.value
        val coeff = JumpControllerState.jumpFullWidthMs.value
        return when (JumpControllerState.runMode.value) {
            RunMode.AUTO_RUNNING -> "自动连跳中 · 累计 ${count} 跳 · 系数 ${coeff}ms"
            RunMode.MANUAL_AIMING -> "双点瞄准中 · 累计 ${count} 跳"
            else -> "悬浮控制条待命 · 累计 ${count} 跳 · 系数 ${coeff}ms"
        }
    }

    fun refreshOverlayFromPrefs() {
        if (!::overlayManager.isInitialized) return
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        ensureForegroundKeepAlive()
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
        ensureForegroundKeepAlive("单步识别中")
        overlayManager.refreshUi()
        executeScreenshotDetectAndJump(isAutoLoop = false)
    }

    override fun onManualAimRequested() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        cancelPendingWatchdogs()
        isCycleBusy = false
        JumpControllerState.setRunMode(RunMode.MANUAL_AIMING)
        JumpControllerState.updateStatus("双点瞄准")
        ensureForegroundKeepAlive("双点瞄准中")
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
                    jumpFullWidthMs = prefs.jumpFullWidthMs,
                    pressOffsetMs = prefs.pressOffsetMs,
                    usePhysicsCurve = prefs.usePhysicsCurve
                )
                val distPx = kotlin.math.abs(x2 - x1)
                isCycleBusy = true
                JumpControllerState.setRunMode(RunMode.PAUSED)
                JumpControllerState.recordJump(distPx, pressMs, "手动跳跃")
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(x1, y1, x2, y2, pressMs)

                mainHandler.postDelayed({
                    dispatchRandomizedLongPress(pressMs, scheduleNextAuto = false)
                }, 70L)
            },
            onCancelled = {
                JumpControllerState.setRunMode(RunMode.PAUSED)
                JumpControllerState.updateStatus("已取消瞄准")
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
            }
        )
    }

    override fun onCoefficientAdjusted(newCoeffMs: Int) {
        ensureForegroundKeepAlive()
    }

    private fun startAutoLoop() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        screenshotRetryCount = 0
        JumpControllerState.setRunMode(RunMode.AUTO_RUNNING)
        JumpControllerState.updateStatus("自动连跳中")
        ensureForegroundKeepAlive()
        ProcessDiagnostics.recordEvent(this, "启动自动连跳(foreground=$isInForeground)")
        overlayManager.refreshUi()
        if (!isCycleBusy) {
            executeScreenshotDetectAndJump(isAutoLoop = true)
        }
    }

    private fun pauseAll(statusText: String) {
        activeCaptureToken++
        activeGestureToken++
        mainHandler.removeCallbacks(scheduledAutoStep)
        cancelPendingWatchdogs()
        isCycleBusy = false
        BitmapPixelExtractor.clearCache()
        JumpControllerState.setRunMode(RunMode.PAUSED)
        JumpControllerState.updateStatus(statusText)
        ensureForegroundKeepAlive()
        if (::overlayManager.isInitialized) {
            overlayManager.removeManualAimOverlay()
            overlayManager.setHiddenForCapture(false)
            overlayManager.refreshUi()
        }
    }

    private fun cancelPendingWatchdogs() {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        gestureWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        gestureWatchdogRunnable = null
    }

    private fun executeScreenshotDetectAndJump(isAutoLoop: Boolean) {
        if (isCycleBusy) return
        isCycleBusy = true
        val token = ++activeCaptureToken

        // Hide floating overlay briefly so SurfaceFlinger captures pure game pixels
        overlayManager.setHiddenForCapture(true)

        val timeoutRunnable = Runnable {
            if (token != activeCaptureToken || !isCycleBusy) return@Runnable
            activeCaptureToken++
            captureTimeoutRunnable = null
            overlayManager.setHiddenForCapture(false)
            isCycleBusy = false
            ProcessDiagnostics.recordEvent(this, "takeScreenshot 回调超时(${SCREENSHOT_TIMEOUT_MS}ms)")
            if (isAutoLoop &&
                JumpControllerState.runMode.value == RunMode.AUTO_RUNNING &&
                screenshotRetryCount < 2
            ) {
                screenshotRetryCount++
                JumpControllerState.updateStatus("截图超时，重试中")
                overlayManager.refreshUi()
                mainHandler.postDelayed(scheduledAutoStep, 900L)
            } else {
                pauseAll("截图超时，请重试")
            }
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, OVERLAY_HIDE_SETTLE_MS + SCREENSHOT_TIMEOUT_MS)

        mainHandler.postDelayed({
            if (token != activeCaptureToken) {
                overlayManager.setHiddenForCapture(false)
                return@postDelayed
            }
            if (isAutoLoop && JumpControllerState.runMode.value != RunMode.AUTO_RUNNING) {
                cancelPendingWatchdogs()
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
                            if (token != activeCaptureToken) {
                                try {
                                    screenshot.hardwareBuffer.close()
                                } catch (_: Throwable) {
                                }
                                return
                            }
                            mainHandler.post {
                                if (token == activeCaptureToken) {
                                    captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                    captureTimeoutRunnable = null
                                    overlayManager.setHiddenForCapture(false)
                                }
                            }
                            val outcome = BitmapPixelExtractor.analyzeScreenshot(
                                screenshotResult = screenshot,
                                jumpFullWidthMs = prefs.jumpFullWidthMs,
                                pressOffsetMs = prefs.pressOffsetMs,
                                usePhysicsCurve = prefs.usePhysicsCurve
                            )
                            mainHandler.post {
                                if (token != activeCaptureToken) return@post
                                handleDetectionOutcome(outcome, isAutoLoop)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            mainHandler.post {
                                if (token != activeCaptureToken) return@post
                                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                captureTimeoutRunnable = null
                                overlayManager.setHiddenForCapture(false)
                                handleScreenshotFailure(errorCode, isAutoLoop)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                cancelPendingWatchdogs()
                overlayManager.setHiddenForCapture(false)
                isCycleBusy = false
                ProcessDiagnostics.recordEvent(this, "截图调用异常: ${t.javaClass.simpleName}")
                pauseAll("截图调用异常")
            }
        }, OVERLAY_HIDE_SETTLE_MS)
    }

    private fun handleScreenshotFailure(errorCode: Int, isAutoLoop: Boolean) {
        isCycleBusy = false
        ProcessDiagnostics.recordEvent(this, "截图失败 code=$errorCode")
        val retryableError = errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ||
            errorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
        if (retryableError &&
            isAutoLoop &&
            JumpControllerState.runMode.value == RunMode.AUTO_RUNNING &&
            screenshotRetryCount < 2
        ) {
            screenshotRetryCount++
            val retryDelay = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 750L else 850L
            JumpControllerState.updateStatus("截图冷却中，稍后重试")
            overlayManager.refreshUi()
            mainHandler.postDelayed(scheduledAutoStep, retryDelay)
            return
        }

        val reason = when {
            errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图过频，请调大间隔"
            errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无截图权限，请重启服务"
            errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "屏幕显示无效"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "画面受隐私保护无法截图"
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
                JumpControllerState.setScreenshotSize(res.bitmapWidth, res.bitmapHeight)
                val statusLabel = if (isAutoLoop) "连跳中" else "单步完成"
                JumpControllerState.recordJump(res.distanceX, res.pressTimeMs, statusLabel)
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(
                    pieceX = res.pieceX,
                    pieceY = res.pieceY,
                    boardX = res.boardX,
                    boardY = res.boardY,
                    pressMs = res.pressTimeMs,
                    sourceWidth = res.bitmapWidth,
                    sourceHeight = res.bitmapHeight
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
        val driftX = Random.nextFloat() * 4f - 2f
        val driftY = Random.nextFloat() * 4f - 2f
        val endX = (startX + driftX).coerceIn(20f, (w - 20).toFloat())
        val endY = (startY + driftY).coerceIn(20f, (h - 20).toFloat())

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, pressTimeMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val gestureToken = ++activeGestureToken

        val fallbackWatchdog = Runnable {
            if (gestureToken != activeGestureToken || !isCycleBusy) return@Runnable
            gestureWatchdogRunnable = null
            isCycleBusy = false
            ProcessDiagnostics.recordEvent(this, "手势回调超时，已自动恢复周期")
            scheduleNextAutoJumpIfRunning(scheduleNextAuto)
        }
        gestureWatchdogRunnable = fallbackWatchdog
        mainHandler.postDelayed(fallbackWatchdog, pressTimeMs + GESTURE_WATCHDOG_EXTRA_MS)

        val dispatched = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (gestureToken != activeGestureToken) return
                        gestureWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        gestureWatchdogRunnable = null
                        isCycleBusy = false
                        scheduleNextAutoJumpIfRunning(scheduleNextAuto)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (gestureToken != activeGestureToken) return
                        gestureWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        gestureWatchdogRunnable = null
                        isCycleBusy = false
                        if (scheduleNextAuto && JumpControllerState.runMode.value == RunMode.AUTO_RUNNING) {
                            ProcessDiagnostics.recordEvent(this, "手势回调 onCancelled")
                            pauseAll("手势被中断，已暂停")
                        }
                    }
                },
                mainHandler
            )
        } catch (t: Throwable) {
            ProcessDiagnostics.recordEvent(this, "dispatchGesture 异常: ${t.javaClass.simpleName}")
            false
        }

        if (!dispatched) {
            gestureWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            gestureWatchdogRunnable = null
            isCycleBusy = false
            pauseAll("手势派发失败")
        }
    }

    private fun scheduleNextAutoJumpIfRunning(scheduleNextAuto: Boolean) {
        if (scheduleNextAuto && JumpControllerState.runMode.value == RunMode.AUTO_RUNNING) {
            val minWait = prefs.minIntervalMs
            val maxWait = prefs.maxIntervalMs
            val nextWait = Random.nextInt(minWait, maxWait + 1).toLong()
            mainHandler.postDelayed(scheduledAutoStep, nextWait)
        }
    }

    private fun pickSafePressCoordinate(w: Int, h: Int, barRect: Rect): Pair<Float, Float> {
        val minX = w * 0.24f
        val maxX = w * 0.76f
        val minY = h * 0.65f
        val maxY = h * 0.79f
        val paddedRect = Rect(barRect.left - 24, barRect.top - 24, barRect.right + 24, barRect.bottom + 24)

        repeat(10) {
            val rx = minX + Random.nextFloat() * (maxX - minX)
            val ry = minY + Random.nextFloat() * (maxY - minY)
            if (paddedRect.isEmpty || !paddedRect.contains(rx.toInt(), ry.toInt())) {
                return rx to ry
            }
        }
        // Fallback outside bar if control bar sits directly inside bottom area
        val fallbackY = if (barRect.centerY() > h * 0.68f) h * 0.54f else h * 0.76f
        return (w * 0.5f) to fallbackY
    }

    companion object {
        private const val OVERLAY_HIDE_SETTLE_MS = 120L
        private const val SCREENSHOT_TIMEOUT_MS = 3500L
        private const val GESTURE_WATCHDOG_EXTRA_MS = 2200L
        private const val KEEP_ALIVE_CHANNEL_ID = "jump_helper_keep_alive"
        private const val KEEP_ALIVE_NOTIFICATION_ID = 1001

        @Volatile
        var instance: JumpAccessibilityService? = null
            private set
    }
}
