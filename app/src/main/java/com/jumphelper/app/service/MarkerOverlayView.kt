package com.jumphelper.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * Non-touchable overlay that displays confidence-coded piece/target reticles and jump duration badge.
 */
class MarkerOverlayView(context: Context) : View(context) {

    private var pieceX = 0f
    private var pieceY = 0f
    private var boardX = 0f
    private var boardY = 0f
    private var badgeText = ""
    private var targetColor = COLOR_HIGH_CONF
    private var hasMarker = false

    private val screenLoc = IntArray(2)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 56, 189, 248)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_HIGH_CONF
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val fillDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_HIGH_CONF
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(228, 15, 23, 42)
        style = Paint.Style.FILL
    }

    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_HIGH_CONF
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 33f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun showMarkers(
        rawPieceX: Float,
        rawPieceY: Float,
        rawBoardX: Float,
        rawBoardY: Float,
        pressDurationMs: Long,
        confidence: Float = 0.90f,
        isWhiteDot: Boolean = false,
        isEscortWarning: Boolean = false
    ) {
        pieceX = rawPieceX
        pieceY = rawPieceY
        boardX = rawBoardX
        boardY = rawBoardY

        targetColor = when {
            isEscortWarning || confidence < 0.48f -> COLOR_LOW_CONF
            confidence < 0.78f -> COLOR_MED_CONF
            else -> COLOR_HIGH_CONF
        }
        targetPaint.color = targetColor
        fillDotPaint.color = targetColor
        badgeStrokePaint.color = targetColor

        badgeText = when {
            isEscortWarning -> "⚠ 复杂块 · 点救场"
            isWhiteDot -> "🎯 ${pressDurationMs}ms"
            confidence < 0.78f -> "注意 · ${pressDurationMs}ms"
            else -> "${pressDurationMs}ms"
        }

        hasMarker = true
        visibility = VISIBLE
        invalidate()
    }

    fun clearMarkers() {
        hasMarker = false
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasMarker) return

        getLocationOnScreen(screenLoc)
        val ox = screenLoc[0].toFloat()
        val oy = screenLoc[1].toFloat()

        val px = pieceX - ox
        val py = pieceY - oy
        val bx = boardX - ox
        val by = boardY - oy

        canvas.drawLine(px, py, bx, by, linePaint)

        // Piece reticle
        canvas.drawCircle(px, py, 24f, piecePaint)
        canvas.drawCircle(px, py, 6f, piecePaint)

        // Target reticle + crosshair
        canvas.drawCircle(bx, by, 26f, targetPaint)
        canvas.drawLine(bx - 36f, by, bx + 36f, by, targetPaint)
        canvas.drawLine(bx, by - 36f, bx, by + 36f, targetPaint)
        canvas.drawCircle(bx, by, 6f, fillDotPaint)

        // Duration / confidence pill near midpoint
        val midX = (px + bx) / 2f
        val midY = (py + by) / 2f - 46f
        val halfW = textPaint.measureText(badgeText) / 2f + 24f
        canvas.drawRoundRect(midX - halfW, midY - 35f, midX + halfW, midY + 14f, 22f, 22f, badgeBgPaint)
        canvas.drawRoundRect(midX - halfW, midY - 35f, midX + halfW, midY + 14f, 22f, 22f, badgeStrokePaint)
        canvas.drawText(badgeText, midX, midY, textPaint)
    }

    companion object {
        private val COLOR_HIGH_CONF = Color.rgb(34, 197, 94)
        private val COLOR_MED_CONF = Color.rgb(245, 158, 11)
        private val COLOR_LOW_CONF = Color.rgb(239, 68, 68)
    }
}
