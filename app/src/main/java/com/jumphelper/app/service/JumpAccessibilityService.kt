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
import com.jumphelper.app.core.AutoCalibrator
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

    private data class CachedPiece(
        val x: Float,
        val y: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val timestampMs: Long
    )

    private lateinit var prefs: JumpPreferences
    private lateinit var overlayManager: FloatingOverlayManager
    private lateinit var autoCalibrator: AutoCalibrator
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var isCycleBusy = false
    private var isInForeground = false
    private var screenshotRetryCount = 0
    private var activeCaptureToken = 0L
    private var activeGestureToken = 0L
    private var lastDispatchedPressMs = 0L
    private var cachedEscortPiece: CachedPiece? = null

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
        autoCalibrator = AutoCalibrator(initialSamples = prefs.calibrationSamples)

        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)
        JumpControllerState.setCalibrationSummary(
            if (prefs.autoCalibrateEnabled) {
                if (prefs.calibrationSamples >= 4) "自校准已收敛 (${prefs.calibrationSamples}次)" else "自校准待机"
            } else {
                "自校准已关闭"
            }
        )
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
        cachedEscortPiece = null
        if (::autoCalibrator.isInitialized) {
            autoCalibrator.clearPending()
        }
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
            RunMode.ESCORT_PAUSED -> "复杂方块已暂停护航 · 累计 ${count} 跳"
            RunMode.MANUAL_AIMING -> "一键救场瞄准中 · 累计 ${count} 跳"
            else -> "悬浮控制条待命 · 累计 ${count} 跳 · 系数 ${coeff}ms"
        }
    }

    fun refreshOverlayFromPrefs() {
        if (!::overlayManager.isInitialized) return
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)
        if (!prefs.autoCalibrateEnabled) {
            JumpControllerState.setCalibrationSummary("自校准已关闭")
        }
        ensureForegroundKeepAlive()
        overlayManager.applyVisibilityFromPrefs()
        overlayManager.refreshUi()
    }

    fun resetAutoCalibration() {
        if (!::prefs.isInitialized) return
        prefs.resetCalibration()
        if (::autoCalibrator.isInitialized) {
            autoCalibrator.resetLearning()
        }
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)
        JumpControllerState.setCalibrationSummary("自校准已重置")
        if (::overlayManager.isInitialized) {
            overlayManager.refreshUi()
        }
        ensureForegroundKeepAlive()
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
        cachedEscortPiece = null
        JumpControllerState.setRunMode(RunMode.PAUSED)
        JumpControllerState.updateStatus("单步识别中")
        ensureForegroundKeepAlive("单步识别中")
        overlayManager.refreshUi()
        executeScreenshotDetectAndJump(isAutoLoop = false)
    }

    override fun onManualAimRequested() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        cancelPendingWatchdogs()
        activeCaptureToken++
        activeGestureToken++
        isCycleBusy = false
        if (::autoCalibrator.isInitialized) {
            autoCalibrator.clearPending()
        }
        JumpControllerState.setRunMode(RunMode.MANUAL_AIMING)
        JumpControllerState.updateStatus("一键救场准备中")
        ensureForegroundKeepAlive("一键救场瞄准中")
        overlayManager.refreshUi()

        val freshCached = cachedEscortPiece?.takeIf {
            System.currentTimeMillis() - it.timestampMs <= ESCORT_PIECE_CACHE_TTL_MS
        }
        if (freshCached != null) {
            val (rawX, rawY) = scaleToDisplay(freshCached.x, freshCached.y, freshCached.sourceWidth, freshCached.sourceHeight)
            openManualAimOverlay(rawX, rawY)
            return
        }

        // Fast piece-only capture so 1-Tap Smart Rescue pre-locks the pawn automatically
        val token = ++activeCaptureToken
        val needHideBar = overlayManager.doesControlBarOverlapScanArea()
        overlayManager.setHiddenForCapture(true, hideControlBar = needHideBar)
        val settleMs = if (needHideBar) OVERLAY_HIDE_SETTLE_MS else 0L

        val fallbackOpen = Runnable {
            if (token != activeCaptureToken) return@Runnable
            activeCaptureToken++
            overlayManager.setHiddenForCapture(false)
            openManualAimOverlay(null, null)
        }
        captureTimeoutRunnable = fallbackOpen
        mainHandler.postDelayed(fallbackOpen, settleMs + PIECE_PRELOCK_TIMEOUT_MS)

        mainHandler.postDelayed({
            if (token != activeCaptureToken) return@postDelayed
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
                            val piece = BitmapPixelExtractor.detectPieceOnly(screenshot)
                            mainHandler.post {
                                if (token != activeCaptureToken) return@post
                                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                captureTimeoutRunnable = null
                                overlayManager.setHiddenForCapture(false)
                                if (piece != null) {
                                    val (rx, ry) = scaleToDisplay(piece.x, piece.y, piece.bitmapWidth, piece.bitmapHeight)
                                    openManualAimOverlay(rx, ry)
                                } else {
                                    openManualAimOverlay(null, null)
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            mainHandler.post {
                                if (token != activeCaptureToken) return@post
                                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                captureTimeoutRunnable = null
                                overlayManager.setHiddenForCapture(false)
                                openManualAimOverlay(null, null)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                captureTimeoutRunnable = null
                overlayManager.setHiddenForCapture(false)
                openManualAimOverlay(null, null)
            }
        }, settleMs)
    }

    private fun openManualAimOverlay(prelockX: Float?, prelockY: Float?) {
        overlayManager.showManualAimOverlay(
            initialPieceRawX = prelockX,
            initialPieceRawY = prelockY,
            onPointsConfirmed = { x1, y1, x2, y2 ->
                cachedEscortPiece = null
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
                JumpControllerState.recordJump(distPx, pressMs, "救场起跳", confidence = 1f, strategyTag = "手动")
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(x1, y1, x2, y2, pressMs, confidence = 1f)

                mainHandler.postDelayed({
                    dispatchRandomizedLongPress(pressMs, scheduleNextAuto = false)
                }, 60L)
            },
            onCancelled = {
                JumpControllerState.setRunMode(RunMode.PAUSED)
                JumpControllerState.updateStatus("已取消救场")
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
            }
        )
    }

    override fun onCoefficientAdjusted(newCoeffMs: Int) {
        if (::autoCalibrator.isInitialized) {
            autoCalibrator.clearPending()
        }
        ensureForegroundKeepAlive()
    }

    private fun startAutoLoop() {
        mainHandler.removeCallbacks(scheduledAutoStep)
        screenshotRetryCount = 0
        cachedEscortPiece = null
        JumpControllerState.setRunMode(RunMode.AUTO_RUNNING)
        JumpControllerState.updateStatus("自动连跳中")
        ensureForegroundKeepAlive()
        ProcessDiagnostics.recordEvent(this, "启动自动连跳(foreground=$isInForeground)")
        overlayManager.refreshUi()
        if (!isCycleBusy) {
            executeScreenshotDetectAndJump(isAutoLoop = true)
        }
    }

    private fun pauseAll(statusText: String, mode: RunMode = RunMode.PAUSED) {
        activeCaptureToken++
        activeGestureToken++
        mainHandler.removeCallbacks(scheduledAutoStep)
        cancelPendingWatchdogs()
        isCycleBusy = false
        if (mode != RunMode.ESCORT_PAUSED) {
            cachedEscortPiece = null
            if (::autoCalibrator.isInitialized) {
                autoCalibrator.clearPending()
            }
        }
        BitmapPixelExtractor.clearCache()
        JumpControllerState.setRunMode(mode)
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

        // Zero-flicker optimization: only hide control bar if it physically overlaps the scan region!
        val needHideBar = overlayManager.doesControlBarOverlapScanArea()
        val hadMarkers = overlayManager.hasVisibleMarkers()
        overlayManager.setHiddenForCapture(true, hideControlBar = needHideBar)
        val settleMs = when {
            needHideBar -> OVERLAY_HIDE_SETTLE_MS
            hadMarkers -> MARKER_CLEAR_SETTLE_MS
            else -> 0L
        }

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
        mainHandler.postDelayed(timeoutRunnable, settleMs + SCREENSHOT_TIMEOUT_MS)

        val captureTask = Runnable {
            if (token != activeCaptureToken) {
                overlayManager.setHiddenForCapture(false)
                return@Runnable
            }
            if (isAutoLoop && JumpControllerState.runMode.value != RunMode.AUTO_RUNNING) {
                cancelPendingWatchdogs()
                overlayManager.setHiddenForCapture(false)
                isCycleBusy = false
                return@Runnable
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
        }

        if (settleMs > 0L) {
            mainHandler.postDelayed(captureTask, settleMs)
        } else {
            captureTask.run()
        }
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
                if (outcome.isGameOverScreen) {
                    ProcessDiagnostics.recordEvent(this, "检测到结算界面，自动暂停")
                }
                pauseAll(outcome.reason)
            }
            is JumpDetector.DetectionOutcome.Success -> {
                screenshotRetryCount = 0
                val res = outcome.result
                JumpControllerState.setScreenshotSize(res.bitmapWidth, res.bitmapHeight)

                // Step 1: Run zero-extra-screenshot closed-loop calibration using previous jump's landing
                val calOutcome = autoCalibrator.evaluateLandingAndUpdate(
                    currentResult = res,
                    currentCoeffMs = prefs.jumpFullWidthMs,
                    currentOffsetMs = prefs.pressOffsetMs,
                    usePhysicsCurve = prefs.usePhysicsCurve,
                    enabled = prefs.autoCalibrateEnabled
                )
                if (calOutcome.applied) {
                    prefs.jumpFullWidthMs = calOutcome.newCoeffMs
                    prefs.pressOffsetMs = calOutcome.newOffsetMs
                    prefs.calibrationSamples = calOutcome.sampleCount
                    JumpControllerState.setJumpFullWidthMs(calOutcome.newCoeffMs)
                    JumpControllerState.setPressOffsetMs(calOutcome.newOffsetMs)
                } else if (calOutcome.isBullseye) {
                    prefs.calibrationSamples = calOutcome.sampleCount
                }
                JumpControllerState.setCalibrationSummary(calOutcome.summaryText)

                // Recompute press duration immediately so THIS jump benefits from updated calibration
                val finalPressMs = if (calOutcome.applied) {
                    JumpDetector.calculateAutoPressTimeMs(
                        distanceX = res.effectiveDistance,
                        shortEdge = res.shortEdge,
                        jumpFullWidthMs = prefs.jumpFullWidthMs,
                        pressOffsetMs = prefs.pressOffsetMs,
                        usePhysicsCurve = prefs.usePhysicsCurve
                    )
                } else {
                    res.pressTimeMs
                }

                val isWhiteDot = res.strategy == JumpDetector.DetectionStrategy.WHITE_DOT

                // Step 2: Smart Escort protection — pause automatically on extreme low-confidence weird blocks
                if (isAutoLoop &&
                    prefs.smartEscortEnabled &&
                    res.confidence < JumpDetector.SMART_ESCORT_CONFIDENCE_THRESHOLD
                ) {
                    cachedEscortPiece = CachedPiece(
                        x = res.pieceX,
                        y = res.pieceY,
                        sourceWidth = res.bitmapWidth,
                        sourceHeight = res.bitmapHeight,
                        timestampMs = System.currentTimeMillis()
                    )
                    ProcessDiagnostics.recordEvent(
                        this,
                        "智能护航暂停(conf=${String.format("%.2f", res.confidence)}, strategy=${res.strategy})"
                    )
                    pauseAll("复杂方块已暂停", mode = RunMode.ESCORT_PAUSED)
                    overlayManager.showDetectionMarkers(
                        pieceX = res.pieceX,
                        pieceY = res.pieceY,
                        boardX = res.boardX,
                        boardY = res.boardY,
                        pressMs = finalPressMs,
                        sourceWidth = res.bitmapWidth,
                        sourceHeight = res.bitmapHeight,
                        confidence = res.confidence,
                        isWhiteDot = false,
                        isEscortWarning = true
                    )
                    return
                }

                // Step 3: Record executed jump & dispatch gesture
                cachedEscortPiece = null
                val strategyTag = when {
                    isWhiteDot -> "🎯靶心"
                    res.confidence >= 0.78f -> "精准"
                    else -> "注意"
                }
                val statusLabel = if (isAutoLoop) "连跳中" else "单步完成"
                JumpControllerState.recordJump(
                    distancePx = res.effectiveDistance,
                    pressMs = finalPressMs,
                    status = statusLabel,
                    confidence = res.confidence,
                    strategyTag = strategyTag
                )
                ensureForegroundKeepAlive()
                overlayManager.refreshUi()
                overlayManager.showDetectionMarkers(
                    pieceX = res.pieceX,
                    pieceY = res.pieceY,
                    boardX = res.boardX,
                    boardY = res.boardY,
                    pressMs = finalPressMs,
                    sourceWidth = res.bitmapWidth,
                    sourceHeight = res.bitmapHeight,
                    confidence = res.confidence,
                    isWhiteDot = isWhiteDot,
                    isEscortWarning = false
                )

                autoCalibrator.recordExecutedJump(res, finalPressMs)
                dispatchRandomizedLongPress(finalPressMs, scheduleNextAuto = isAutoLoop)
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
        lastDispatchedPressMs = pressTimeMs

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
                            ProcessDiagnostics.recordEvent(this@JumpAccessibilityService, "手势回调 onCancelled")
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
            val baseWait = Random.nextInt(minWait, maxWait + 1).toLong()
            // Flight-aware cushion: long jumps spend more time flying & panning camera
            val flightCushion = ((lastDispatchedPressMs - 560L).coerceIn(0L, 420L) * 0.65f).toLong()
            mainHandler.postDelayed(scheduledAutoStep, baseWait + flightCushion)
        }
    }

    private fun scaleToDisplay(x: Float, y: Float, sourceW: Int, sourceH: Int): Pair<Float, Float> {
        val bounds = overlayManager.getDisplayBounds()
        val sx = if (sourceW > 0 && bounds.width() > 0) bounds.width() / sourceW.toFloat() else 1f
        val sy = if (sourceH > 0 && bounds.height() > 0) bounds.height() / sourceH.toFloat() else 1f
        return (x * sx) to (y * sy)
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
        val fallbackY = if (barRect.centerY() > h * 0.68f) h * 0.54f else h * 0.76f
        return (w * 0.5f) to fallbackY
    }

    companion object {
        private const val OVERLAY_HIDE_SETTLE_MS = 110L
        private const val MARKER_CLEAR_SETTLE_MS = 45L
        private const val SCREENSHOT_TIMEOUT_MS = 3500L
        private const val PIECE_PRELOCK_TIMEOUT_MS = 650L
        private const val ESCORT_PIECE_CACHE_TTL_MS = 20_000L
        private const val GESTURE_WATCHDOG_EXTRA_MS = 2200L
        private const val KEEP_ALIVE_CHANNEL_ID = "jump_helper_keep_alive"
        private const val KEEP_ALIVE_NOTIFICATION_ID = 1001

        @Volatile
        var instance: JumpAccessibilityService? = null
            private set
    }
}
