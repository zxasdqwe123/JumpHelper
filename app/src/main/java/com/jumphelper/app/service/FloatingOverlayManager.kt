package com.jumphelper.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jumphelper.app.R
import com.jumphelper.app.core.JumpControllerState
import com.jumphelper.app.core.JumpDetector
import com.jumphelper.app.core.JumpPreferences
import com.jumphelper.app.core.RunMode
import kotlin.math.abs

class FloatingOverlayManager(
    private val context: Context,
    private val prefs: JumpPreferences,
    private val listener: OverlayActionListener
) {
    interface OverlayActionListener {
        fun onAutoToggleRequested()
        fun onSingleStepRequested()
        fun onManualAimRequested()
        fun onCoefficientAdjusted(newCoeffMs: Int)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var controlRootView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var collapsedPill: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var tuningRow: LinearLayout? = null
    private var btnToggleTune: TextView? = null
    private var tvOverlayStatus: TextView? = null
    private var tvLastJump: TextView? = null
    private var btnAutoToggle: TextView? = null
    private var btnSingleStep: TextView? = null
    private var btnManualAim: TextView? = null
    private var tvCoeffOverlay: TextView? = null

    private var markerOverlayView: MarkerOverlayView? = null
    private var manualAimView: ManualAimOverlayView? = null

    private var isCollapsed = false
    private var isTuningExpanded = false
    private val clearMarkerRunnable = Runnable {
        markerOverlayView?.clearMarkers()
    }

    fun getDisplayBounds(): Rect = windowManager.maximumWindowMetrics.bounds

    @SuppressLint("InflateParams")
    fun attach() {
        ensureControlWindow()
        applyVisibilityFromPrefs()
        refreshUi()
    }

    fun detach() {
        mainHandler.removeCallbacks(clearMarkerRunnable)
        removeManualAimOverlay()
        markerOverlayView?.let { safeRemoveView(it) }
        markerOverlayView = null
        controlRootView?.let { safeRemoveView(it) }
        controlRootView = null
        controlParams = null
    }

    fun applyVisibilityFromPrefs() {
        val visible = prefs.showOverlay && manualAimView == null
        controlRootView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun resetPosition() {
        prefs.resetOverlayPosition()
        val params = controlParams ?: return
        val bounds = getDisplayBounds()
        // Default bottom-safe parking spot sits outside the gameplay scan band -> zero flicker!
        params.x = dp(16)
        params.y = (bounds.height() * 0.84f).toInt()
        controlRootView?.let { view ->
            clampParamsToScreen(view, params)
            safeUpdateViewLayout(view, params)
        }
    }

    /**
     * Checks whether the floating control bar currently intersects the active gameplay scan area.
     * If false, we can capture screenshots without hiding the control bar (zero flicker!).
     */
    fun doesControlBarOverlapScanArea(): Boolean {
        val barRect = getControlBarRect()
        if (barRect.isEmpty) return false
        val bounds = getDisplayBounds()
        val (scanTop, scanBottom) = JumpDetector.getScanVerticalBounds(bounds.width(), bounds.height())
        val safeMargin = dp(14)
        return barRect.bottom > (scanTop - safeMargin) && barRect.top < (scanBottom + safeMargin)
    }

    fun hasVisibleMarkers(): Boolean {
        return markerOverlayView?.visibility == View.VISIBLE
    }

    fun setHiddenForCapture(hidden: Boolean, hideControlBar: Boolean = true) {
        if (hidden) {
            mainHandler.removeCallbacks(clearMarkerRunnable)
            markerOverlayView?.clearMarkers()
            if (hideControlBar) {
                controlRootView?.visibility = View.INVISIBLE
            }
        } else {
            applyVisibilityFromPrefs()
        }
    }

    fun showDetectionMarkers(
        pieceX: Float,
        pieceY: Float,
        boardX: Float,
        boardY: Float,
        pressMs: Long,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        confidence: Float = 0.90f,
        isWhiteDot: Boolean = false,
        isEscortWarning: Boolean = false
    ) {
        if (!prefs.showDebugMarker && !isEscortWarning) return
        ensureMarkerWindow()
        val bounds = getDisplayBounds()
        val scaleX = if (sourceWidth > 0 && bounds.width() > 0) bounds.width() / sourceWidth.toFloat() else 1f
        val scaleY = if (sourceHeight > 0 && bounds.height() > 0) bounds.height() / sourceHeight.toFloat() else 1f

        markerOverlayView?.showMarkers(
            rawPieceX = pieceX * scaleX,
            rawPieceY = pieceY * scaleY,
            rawBoardX = boardX * scaleX,
            rawBoardY = boardY * scaleY,
            pressDurationMs = pressMs,
            confidence = confidence,
            isWhiteDot = isWhiteDot,
            isEscortWarning = isEscortWarning
        )
        mainHandler.removeCallbacks(clearMarkerRunnable)
        val duration = if (isEscortWarning) ESCORT_MARKER_DISPLAY_MS else MARKER_DISPLAY_MS
        mainHandler.postDelayed(clearMarkerRunnable, duration)
    }

    fun getControlBarRect(): Rect {
        val view = controlRootView ?: return Rect()
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return Rect()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    fun showManualAimOverlay(
        initialPieceRawX: Float? = null,
        initialPieceRawY: Float? = null,
        onPointsConfirmed: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
        onCancelled: () -> Unit
    ) {
        removeManualAimOverlay()
        mainHandler.removeCallbacks(clearMarkerRunnable)
        markerOverlayView?.clearMarkers()
        controlRootView?.visibility = View.GONE

        val aimView = ManualAimOverlayView(
            context = context,
            initialPieceRawX = initialPieceRawX,
            initialPieceRawY = initialPieceRawY,
            onPointsConfirmed = { x1, y1, x2, y2 ->
                removeManualAimOverlay()
                onPointsConfirmed(x1, y1, x2, y2)
            },
            onCancelled = {
                removeManualAimOverlay()
                onCancelled()
            }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (safeAddView(aimView, params)) {
            manualAimView = aimView
        } else {
            applyVisibilityFromPrefs()
            onCancelled()
        }
    }

    fun removeManualAimOverlay() {
        manualAimView?.let { safeRemoveView(it) }
        manualAimView = null
        applyVisibilityFromPrefs()
    }

    fun refreshUi() {
        val mode = JumpControllerState.runMode.value
        val count = JumpControllerState.jumpCount.value
        val status = JumpControllerState.lastStatusText.value
        val coeff = JumpControllerState.jumpFullWidthMs.value
        val dist = JumpControllerState.lastDistancePx.value
        val pressMs = JumpControllerState.lastPressMs.value
        val strategyTag = JumpControllerState.lastStrategyTag.value
        val calSummary = JumpControllerState.calibrationSummary.value

        tvCoeffOverlay?.text = coeff.toString()

        when (mode) {
            RunMode.AUTO_RUNNING -> {
                val tagSuffix = if (strategyTag.isNotEmpty()) " · $strategyTag" else ""
                tvOverlayStatus?.text = "连跳 #${count}$tagSuffix"
                btnAutoToggle?.text = "⏸ 暂停"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_green)
                collapsedPill?.text = if (count > 0) "#$count" else "跳"
            }
            RunMode.ESCORT_PAUSED -> {
                tvOverlayStatus?.text = "⚠ 复杂块暂停 · #${count}"
                btnAutoToggle?.text = "▶ 继续"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_amber)
                collapsedPill?.text = "⚠"
            }
            RunMode.MANUAL_AIMING -> {
                tvOverlayStatus?.text = "一键救场瞄准中"
                btnAutoToggle?.text = "▶ 自动"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_accent)
                collapsedPill?.text = "🎯"
            }
            RunMode.PAUSED -> {
                tvOverlayStatus?.text = "$status · ${count}跳"
                btnAutoToggle?.text = "▶ 继续"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_amber)
                collapsedPill?.text = "跳"
            }
            RunMode.IDLE -> {
                tvOverlayStatus?.text = "$status · ${count}跳"
                btnAutoToggle?.text = "▶ 自动"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_accent)
                collapsedPill?.text = "跳"
            }
        }

        tvLastJump?.text = when {
            mode == RunMode.ESCORT_PAUSED -> "请点「🎯 救场」一键起跳，或点单步强跳"
            pressMs > 0L -> "${dist.toInt()}px→${pressMs}ms · $calSummary"
            prefs.autoCalibrateEnabled -> "智能自校准已开启 · 拖到底部零闪烁"
            else -> "已关闭自校准 · 点「调参」可微调系数"
        }
    }

    private fun ensureMarkerWindow() {
        if (markerOverlayView != null) return
        val marker = MarkerOverlayView(context).apply {
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (safeAddView(marker, params)) {
            markerOverlayView = marker
        }
    }

    @SuppressLint("InflateParams")
    private fun ensureControlWindow() {
        if (controlRootView != null) return

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_control_bar, null)
        collapsedPill = root.findViewById(R.id.collapsedPill)
        expandedPanel = root.findViewById(R.id.expandedPanel)
        tuningRow = root.findViewById(R.id.tuningRow)
        btnToggleTune = root.findViewById(R.id.btnToggleTune)
        tvOverlayStatus = root.findViewById(R.id.tvOverlayStatus)
        tvLastJump = root.findViewById(R.id.tvLastJump)
        btnAutoToggle = root.findViewById(R.id.btnAutoToggle)
        btnSingleStep = root.findViewById(R.id.btnSingleStep)
        btnManualAim = root.findViewById(R.id.btnManualAim)
        tvCoeffOverlay = root.findViewById(R.id.tvCoeffOverlay)

        val dragHeader: View = root.findViewById(R.id.dragHeader)
        val btnCollapse: View = root.findViewById(R.id.btnCollapse)
        val btnMinus50: View = root.findViewById(R.id.btnMinus50)
        val btnMinus15: View = root.findViewById(R.id.btnMinus15)
        val btnPlus15: View = root.findViewById(R.id.btnPlus15)
        val btnPlus50: View = root.findViewById(R.id.btnPlus50)

        val bounds = getDisplayBounds()
        val initX = if (prefs.overlayX != Int.MIN_VALUE) {
            prefs.overlayX.coerceIn(0, (bounds.width() - dp(60)).coerceAtLeast(0))
        } else {
            dp(16)
        }
        val initY = if (prefs.overlayY != Int.MIN_VALUE) {
            prefs.overlayY.coerceIn(0, (bounds.height() - dp(60)).coerceAtLeast(0))
        } else {
            // Default park at bottom (outside gameplay scan band) for zero-flicker capture
            (bounds.height() * 0.84f).toInt()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }

        setupDragListener(dragHeader, root, params, onTap = null)
        setupDragListener(collapsedPill!!, root, params) {
            setCollapsed(false)
        }

        btnCollapse.setOnClickListener { setCollapsed(true) }
        btnToggleTune?.setOnClickListener {
            isTuningExpanded = !isTuningExpanded
            tuningRow?.visibility = if (isTuningExpanded) View.VISIBLE else View.GONE
            btnToggleTune?.text = if (isTuningExpanded) "收起调参" else "调参"
            controlRootView?.let { view ->
                controlParams?.let { p ->
                    clampParamsToScreen(view, p)
                    safeUpdateViewLayout(view, p)
                }
            }
        }
        btnAutoToggle?.setOnClickListener { listener.onAutoToggleRequested() }
        btnSingleStep?.setOnClickListener { listener.onSingleStepRequested() }
        btnManualAim?.setOnClickListener { listener.onManualAimRequested() }

        btnMinus50.setOnClickListener { adjustCoeff(-50) }
        btnMinus15.setOnClickListener { adjustCoeff(-15) }
        btnPlus15.setOnClickListener { adjustCoeff(15) }
        btnPlus50.setOnClickListener { adjustCoeff(50) }

        tvCoeffOverlay?.setOnClickListener {
            Toast.makeText(
                context,
                "跳近调大(+)、跳远调小(-)；长按重置为 ${JumpDetector.RECOMMENDED_INITIAL_COEFF_MS}ms",
                Toast.LENGTH_SHORT
            ).show()
        }
        tvCoeffOverlay?.setOnLongClickListener {
            prefs.resetCalibration()
            JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
            JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)
            listener.onCoefficientAdjusted(prefs.jumpFullWidthMs)
            refreshUi()
            Toast.makeText(context, "已重置系数与自校准记录", Toast.LENGTH_SHORT).show()
            true
        }

        if (safeAddView(root, params)) {
            controlRootView = root
            controlParams = params
        }
    }

    private fun adjustCoeff(delta: Int) {
        val next = (prefs.jumpFullWidthMs + delta).coerceIn(
            JumpPreferences.MIN_COEFF_MS,
            JumpPreferences.MAX_COEFF_MS
        )
        prefs.jumpFullWidthMs = next
        JumpControllerState.setJumpFullWidthMs(next)
        listener.onCoefficientAdjusted(next)
        refreshUi()
    }

    private fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        collapsedPill?.visibility = if (collapsed) View.VISIBLE else View.GONE
        expandedPanel?.visibility = if (collapsed) View.GONE else View.VISIBLE
        controlRootView?.let { view ->
            controlParams?.let { params ->
                clampParamsToScreen(view, params)
                safeUpdateViewLayout(view, params)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)?
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampParamsToScreen(root, params)
                        safeUpdateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampParamsToScreen(root: View, params: WindowManager.LayoutParams) {
        val bounds = getDisplayBounds()
        val viewW = if (root.width > 0) root.width else dp(48)
        val viewH = if (root.height > 0) root.height else dp(48)
        val maxX = (bounds.width() - viewW).coerceAtLeast(0)
        val maxY = (bounds.height() - viewH).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun safeAddView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Throwable) {
        }
    }

    private fun safeRemoveView(view: View) {
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        } catch (_: Throwable) {
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    companion object {
        private const val MARKER_DISPLAY_MS = 1250L
        private const val ESCORT_MARKER_DISPLAY_MS = 3800L
    }
}
