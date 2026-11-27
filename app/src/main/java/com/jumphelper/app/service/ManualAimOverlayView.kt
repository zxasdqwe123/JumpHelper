package com.jumphelper.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen interactive rescue overlay.
 *
 * When a pre-detected pawn position is supplied (1-Tap Smart Rescue), the start reticle is
 * pre-locked on the pawn base so the user only needs a single tap on the target block center.
 * Tapping "重选" anytime switches to classic 2-point selection.
 */
class ManualAimOverlayView(
    context: Context,
    initialPieceRawX: Float? = null,
    initialPieceRawY: Float? = null,
    private val onPointsConfirmed: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
    private val onCancelled: () -> Unit
) : FrameLayout(context) {

    private var hasStartPoint = initialPieceRawX != null && initialPieceRawY != null
    private var startRawX = initialPieceRawX ?: 0f
    private var startRawY = initialPieceRawY ?: 0f

    private val titleView: TextView
    private val resetBtn: TextView
    private val canvasView: AimCanvasView
    private val screenLoc = IntArray(2)

    init {
        setBackgroundColor(Color.argb(42, 0, 0, 0))

        canvasView = AimCanvasView(context)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(236, 15, 23, 42))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(150, 56, 189, 248))
            }
        }

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            text = if (hasStartPoint) {
                "一键救场：已锁棋子 · 点【目标中心】即跳"
            } else {
                "双点瞄准 (1/2)：点击【棋子底座】"
            }
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        resetBtn = TextView(context).apply {
            setTextColor(Color.rgb(251, 191, 36))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            text = "重选起点"
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(10).toFloat()
            }
            visibility = if (hasStartPoint) VISIBLE else GONE
            setOnClickListener {
                resetSelection()
            }
        }
        val resetParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        topBar.addView(resetBtn, resetParams)

        val cancelBtn = TextView(context).apply {
            setTextColor(Color.rgb(248, 113, 113))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            text = "取消"
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                onCancelled()
            }
        }
        topBar.addView(cancelBtn)

        val barParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(48)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        addView(topBar, barParams)
    }

    fun resetSelection() {
        hasStartPoint = false
        titleView.text = "双点瞄准 (1/2)：点击【棋子底座】"
        resetBtn.visibility = GONE
        canvasView.invalidate()
    }

    private inner class AimCanvasView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
            strokeWidth = 6f
            style = Paint.Style.STROKE
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
            style = Paint.Style.FILL
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 34, 197, 94)
            strokeWidth = 4f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (!hasStartPoint) {
                    hasStartPoint = true
                    startRawX = event.rawX
                    startRawY = event.rawY
                    titleView.text = "双点瞄准 (2/2)：点击【目标中心】即刻起跳"
                    resetBtn.visibility = VISIBLE
                    invalidate()
                } else {
                    val endRawX = event.rawX
                    val endRawY = event.rawY
                    onPointsConfirmed(startRawX, startRawY, endRawX, endRawY)
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hasStartPoint) {
                getLocationOnScreen(screenLoc)
                val sx = startRawX - screenLoc[0].toFloat()
                val sy = startRawY - screenLoc[1].toFloat()

                canvas.drawCircle(sx, sy, 32f, ringPaint)
                canvas.drawLine(sx - 46f, sy, sx + 46f, sy, ringPaint)
                canvas.drawLine(sx, sy - 46f, sx, sy + 46f, ringPaint)
                canvas.drawCircle(sx, sy, 8f, centerPaint)

                // Draw subtle 30-degree isometric reference rays from start point to aid manual aiming
                val rayLen = width * 0.48f
                val dx = rayLen * 0.866f
                val dy = rayLen * 0.5f
                canvas.drawLine(sx, sy, sx - dx, sy - dy, guidePaint)
                canvas.drawLine(sx, sy, sx + dx, sy - dy, guidePaint)
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}
