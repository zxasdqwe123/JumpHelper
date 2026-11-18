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
    private var tvOverlayStatus: TextView? = null
    private var tvLastJump: TextView? = null
    private var btnAutoToggle: TextView? = null
    private var btnSingleStep: TextView? = null
    private var btnManualAim: TextView? = null
    private var tvCoeffOverlay: TextView? = null

    private var markerOverlayView: MarkerOverlayView? = null
    private var manualAimView: ManualAimOverlayView? = null

    private var isCollapsed = false
    private val clearMarkerRunnable = Runnable {
        markerOverlayView?.clearMarkers()
    }

    fun getDisplayBounds(): Rect = windowManager.maximumWindowMetrics.bounds

    @SuppressLint("InflateParams")
    fun attach() {
        ensureMarkerWindow()
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
        params.x = dp(12)
        params.y = (bounds.height() * 0.14f).toInt()
        controlRootView?.let { view ->
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    fun setHiddenForCapture(hidden: Boolean) {
        if (hidden) {
            mainHandler.removeCallbacks(clearMarkerRunnable)
            markerOverlayView?.clearMarkers()
            controlRootView?.visibility = View.INVISIBLE
        } else {
            applyVisibilityFromPrefs()
        }
    }

    fun showDetectionMarkers(
        pieceX: Float,
        pieceY: Float,
        boardX: Float,
        boardY: Float,
        pressMs: Long
    ) {
        if (!prefs.showDebugMarker) return
        ensureMarkerWindow()
        markerOverlayView?.showMarkers(pieceX, pieceY, boardX, boardY, pressMs)
        mainHandler.removeCallbacks(clearMarkerRunnable)
        mainHandler.postDelayed(clearMarkerRunnable, 550L)
    }

    fun getControlBarRect(): Rect {
        val view = controlRootView ?: return Rect()
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return Rect()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    fun showManualAimOverlay(
        onPointsConfirmed: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
        onCancelled: () -> Unit
    ) {
        removeManualAimOverlay()
        controlRootView?.visibility = View.GONE

        val aimView = ManualAimOverlayView(
            context = context,
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
        windowManager.addView(aimView, params)
        manualAimView = aimView
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

        tvCoeffOverlay?.text = coeff.toString()

        when (mode) {
            RunMode.AUTO_RUNNING -> {
                tvOverlayStatus?.text = "连跳中 · #${count}"
                btnAutoToggle?.text = "⏸ 暂停"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_green)
                collapsedPill?.text = "跳"
            }
            RunMode.MANUAL_AIMING -> {
                tvOverlayStatus?.text = "双点瞄准中"
                btnAutoToggle?.text = "▶ 自动"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_accent)
            }
            RunMode.PAUSED -> {
                tvOverlayStatus?.text = "$status · ${count}跳"
                btnAutoToggle?.text = "▶ 继续"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_amber)
            }
            RunMode.IDLE -> {
                tvOverlayStatus?.text = "$status · ${count}跳"
                btnAutoToggle?.text = "▶ 自动"
                btnAutoToggle?.setBackgroundResource(R.drawable.bg_overlay_btn_accent)
            }
        }

        tvLastJump?.text = if (pressMs > 0L) {
            "上次: ${dist.toInt()}px → ${pressMs}ms · 长按系数重置"
        } else {
            "系数长按重置1700 · 拖动顶部移动"
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
        windowManager.addView(marker, params)
        markerOverlayView = marker
    }

    @SuppressLint("InflateParams")
    private fun ensureControlWindow() {
        if (controlRootView != null) return

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_control_bar, null)
        collapsedPill = root.findViewById(R.id.collapsedPill)
        expandedPanel = root.findViewById(R.id.expandedPanel)
        tvOverlayStatus = root.findViewById(R.id.tvOverlayStatus)
        tvLastJump = root.findViewById(R.id.tvLastJump)
        btnAutoToggle = root.findViewById(R.id.btnAutoToggle)
        btnSingleStep = root.findViewById(R.id.btnSingleStep)
        btnManualAim = root.findViewById(R.id.btnManualAim)
        tvCoeffOverlay = root.findViewById(R.id.tvCoeffOverlay)

        val dragHeader: View = root.findViewById(R.id.dragHeader)
        val btnCollapse: View = root.findViewById(R.id.btnCollapse)
        val btnMinus20: View = root.findViewById(R.id.btnMinus20)
        val btnMinus5: View = root.findViewById(R.id.btnMinus5)
        val btnPlus5: View = root.findViewById(R.id.btnPlus5)
        val btnPlus20: View = root.findViewById(R.id.btnPlus20)

        val bounds = getDisplayBounds()
        val initX = if (prefs.overlayX != Int.MIN_VALUE) {
            prefs.overlayX.coerceIn(0, (bounds.width() - dp(60)).coerceAtLeast(0))
        } else {
            dp(12)
        }
        val initY = if (prefs.overlayY != Int.MIN_VALUE) {
            prefs.overlayY.coerceIn(0, (bounds.height() - dp(60)).coerceAtLeast(0))
        } else {
            (bounds.height() * 0.14f).toInt()
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
        btnAutoToggle?.setOnClickListener { listener.onAutoToggleRequested() }
        btnSingleStep?.setOnClickListener { listener.onSingleStepRequested() }
        btnManualAim?.setOnClickListener { listener.onManualAimRequested() }

        btnMinus20.setOnClickListener { adjustCoeff(-20) }
        btnMinus5.setOnClickListener { adjustCoeff(-5) }
        btnPlus5.setOnClickListener { adjustCoeff(5) }
        btnPlus20.setOnClickListener { adjustCoeff(20) }

        tvCoeffOverlay?.setOnClickListener {
            Toast.makeText(context, "长按系数可重置为 ${JumpDetector.DEFAULT_JUMP_FULL_WIDTH_MS}ms", Toast.LENGTH_SHORT).show()
        }
        tvCoeffOverlay?.setOnLongClickListener {
            prefs.jumpFullWidthMs = JumpDetector.DEFAULT_JUMP_FULL_WIDTH_MS
            JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
            listener.onCoefficientAdjusted(prefs.jumpFullWidthMs)
            refreshUi()
            true
        }

        windowManager.addView(root, params)
        controlRootView = root
        controlParams = params
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
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, params)
                }
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
                        if (root.isAttachedToWindow) {
                            windowManager.updateViewLayout(root, params)
                        }
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
        val viewW = if (root.width > 0) root.width else dp(46)
        val viewH = if (root.height > 0) root.height else dp(46)
        val maxX = (bounds.width() - viewW).coerceAtLeast(0)
        val maxY = (bounds.height() - viewH).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
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
}
