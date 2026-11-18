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
 * Full-screen interactive aiming overlay for manual two-point fallback.
 * Step 1: tap piece base; Step 2: tap target block center.
 */
class ManualAimOverlayView(
    context: Context,
    private val onPointsConfirmed: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
    private val onCancelled: () -> Unit
) : FrameLayout(context) {

    private var hasStartPoint = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var startLocalX = 0f
    private var startLocalY = 0f

    private val titleView: TextView
    private val resetBtn: TextView
    private val canvasView: AimCanvasView

    init {
        setBackgroundColor(Color.argb(46, 0, 0, 0))

        canvasView = AimCanvasView(context)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 15, 23, 42))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(140, 56, 189, 248))
            }
        }

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            text = "双点瞄准 (1/2)：点击【棋子底座】"
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        resetBtn = TextView(context).apply {
            setTextColor(Color.rgb(251, 191, 36))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            text = "重选"
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(10).toFloat()
            }
            visibility = GONE
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
            color = Color.argb(180, 34, 197, 94)
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
                    startLocalX = event.x
                    startLocalY = event.y
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
                canvas.drawCircle(startLocalX, startLocalY, 32f, ringPaint)
                canvas.drawLine(startLocalX - 46f, startLocalY, startLocalX + 46f, startLocalY, ringPaint)
                canvas.drawLine(startLocalX, startLocalY - 46f, startLocalX, startLocalY + 46f, ringPaint)
                canvas.drawCircle(startLocalX, startLocalY, 8f, centerPaint)
                // Draw subtle 30-degree isometric reference rays from start point to aid manual aiming
                val rayLen = width * 0.45f
                val dx = rayLen * 0.866f
                val dy = rayLen * 0.5f
                canvas.drawLine(startLocalX, startLocalY, startLocalX - dx, startLocalY - dy, guidePaint)
                canvas.drawLine(startLocalX, startLocalY, startLocalX + dx, startLocalY - dy, guidePaint)
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
