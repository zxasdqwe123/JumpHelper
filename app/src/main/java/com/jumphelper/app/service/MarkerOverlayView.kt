package com.jumphelper.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * Non-touchable overlay that briefly displays detected piece and target markers.
 */
class MarkerOverlayView(context: Context) : View(context) {

    private var pieceX = 0f
    private var pieceY = 0f
    private var boardX = 0f
    private var boardY = 0f
    private var pressMs = 0L
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
        color = Color.rgb(34, 197, 94)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val fillDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 15, 23, 42)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun showMarkers(
        rawPieceX: Float,
        rawPieceY: Float,
        rawBoardX: Float,
        rawBoardY: Float,
        pressDurationMs: Long
    ) {
        pieceX = rawPieceX
        pieceY = rawPieceY
        boardX = rawBoardX
        boardY = rawBoardY
        pressMs = pressDurationMs
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

        // Duration pill near midpoint
        val midX = (px + bx) / 2f
        val midY = (py + by) / 2f - 44f
        val label = "${pressMs}ms"
        val halfW = textPaint.measureText(label) / 2f + 22f
        canvas.drawRoundRect(midX - halfW, midY - 34f, midX + halfW, midY + 14f, 20f, 20f, badgeBgPaint)
        canvas.drawText(label, midX, midY, textPaint)
    }
}
