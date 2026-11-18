package com.jumphelper.app.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pure Kotlin pixel recognition engine for WeChat Jump (跳一跳).
 *
 * Inspired by the short-edge adaptive algorithm in wangshub/wechat_jump_game
 * (`wechat_jump_auto_slim.py`, Apache-2.0) and enhanced with multi-row noise
 * filtering, edge margins for curved/punch-hole displays, and cluster median filtering.
 */
object JumpDetector {

    const val DEFAULT_JUMP_FULL_WIDTH_MS = 1700
    const val MIN_PRESS_MS = 200L
    const val MAX_PRESS_MS = 2800L

    // Isometric board slope is 25.5 / 43.5 (~30.38 deg), cos(30.38 deg) ≈ 0.8627
    private const val ISOMETRIC_COS = 0.8627f
    private const val ISOMETRIC_SLOPE = 25.5f / 43.5f

    data class DetectionResult(
        val pieceX: Float,
        val pieceY: Float,
        val boardX: Float,
        val boardY: Float,
        val boardTopY: Float,
        val distanceX: Float,
        val shortEdge: Int,
        val pressTimeMs: Long
    )

    sealed class DetectionOutcome {
        data class Success(val result: DetectionResult) : DetectionOutcome()
        data class Failure(val reason: String) : DetectionOutcome()
    }

    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        jumpFullWidthMs: Int = DEFAULT_JUMP_FULL_WIDTH_MS
    ): DetectionOutcome {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return DetectionOutcome.Failure("截图尺寸异常 (${width}x$height)")
        }

        val shortEdge = min(width, height)
        val scanTop = ((height - shortEdge) / 2).coerceAtLeast((height * 0.14f).toInt())
        val scanBottom = ((height + shortEdge) / 2).coerceAtMost((height * 0.86f).toInt())
        if (scanBottom <= scanTop + 20) {
            return DetectionOutcome.Failure("有效扫描区域过小")
        }

        val piece = findPiece(pixels, width, height, shortEdge, scanTop, scanBottom)
            ?: return DetectionOutcome.Failure("未检测到棋子（请确认处于跳一跳游戏界面）")

        val board = findBoard(pixels, width, height, shortEdge, scanTop, scanBottom, piece.x, piece.y)
            ?: return DetectionOutcome.Failure("未检测到目标方块（可使用双点手动瞄准）")

        val distanceX = abs(board.x - piece.x)
        val pressTimeMs = calculateAutoPressTimeMs(distanceX, shortEdge, jumpFullWidthMs)

        return DetectionOutcome.Success(
            DetectionResult(
                pieceX = piece.x,
                pieceY = piece.y,
                boardX = board.x,
                boardY = board.y,
                boardTopY = board.topY,
                distanceX = distanceX,
                shortEdge = shortEdge,
                pressTimeMs = pressTimeMs
            )
        )
    }

    fun calculateAutoPressTimeMs(
        distanceX: Float,
        shortEdge: Int,
        jumpFullWidthMs: Int
    ): Long {
        if (shortEdge <= 0 || distanceX <= 1f) return MIN_PRESS_MS
        val jumpPercent = distanceX / shortEdge.toFloat()
        return (jumpFullWidthMs * jumpPercent).roundToLong().coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
    }

    fun calculateManualPressTimeMs(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        screenWidth: Int,
        screenHeight: Int,
        jumpFullWidthMs: Int
    ): Long {
        val shortEdge = min(screenWidth, screenHeight).coerceAtLeast(1)
        val euclideanDist = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (euclideanDist <= 1f) return MIN_PRESS_MS
        val equivalentDx = euclideanDist * ISOMETRIC_COS
        val jumpPercent = equivalentDx / shortEdge.toFloat()
        return (jumpFullWidthMs * jumpPercent).roundToLong().coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
    }

    private data class Point(val x: Float, val y: Float)
    private data class BoardPoint(val x: Float, val y: Float, val topY: Float)

    private fun findPiece(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        scanBottom: Int
    ): Point? {
        val coarseStep = max(2, shortEdge / 40)
        val edgeMargin = max(4, width / 50)
        val pieceWidth = max(12, shortEdge / 14)
        val pieceHeight = max(24, shortEdge / 5)
        val fineStepY = max(1, shortEdge / 270)
        val minRowHits = max(5, shortEdge / 90)

        var ny = scanBottom - 1
        while (ny > scanTop) {
            val rowOffset = ny * width
            var nx = edgeMargin
            while (nx < width - edgeMargin) {
                if (isPieceColor(pixels[rowOffset + nx])) {
                    val refined = refinePiecePosition(
                        pixels = pixels,
                        width = width,
                        height = height,
                        shortEdge = shortEdge,
                        seedX = nx,
                        seedY = ny,
                        coarseStep = coarseStep,
                        pieceWidth = pieceWidth,
                        pieceHeight = pieceHeight,
                        fineStepY = fineStepY,
                        minRowHits = minRowHits,
                        scanTop = scanTop
                    )
                    if (refined != null) return refined
                }
                nx += coarseStep
            }
            ny -= coarseStep
        }
        return null
    }

    private fun refinePiecePosition(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        seedX: Int,
        seedY: Int,
        coarseStep: Int,
        pieceWidth: Int,
        pieceHeight: Int,
        fineStepY: Int,
        minRowHits: Int,
        scanTop: Int
    ): Point? {
        val fineStartY = min(height - 1, seedY + coarseStep)
        val fineEndY = max(scanTop, seedY - pieceHeight)
        val xStart = max(0, seedX - pieceWidth)
        val xEnd = min(width - 1, seedX + pieceWidth)

        var fy = fineStartY
        while (fy >= fineEndY) {
            val hits = collectPieceRowHits(pixels, width, fy, xStart, xEnd)
            if (hits.size >= minRowHits) {
                val baseAvgX = hits.average().toFloat()
                // Combine up to 2 adjacent rows above if consistent to smooth anti-aliasing
                var totalX = 0.0
                var totalCount = 0
                for (step in 0..2) {
                    val rowY = fy - step * fineStepY
                    if (rowY < fineEndY) break
                    val rowHits = if (step == 0) hits else collectPieceRowHits(pixels, width, rowY, xStart, xEnd)
                    if (rowHits.size >= minRowHits) {
                        val rowAvg = rowHits.average().toFloat()
                        if (abs(rowAvg - baseAvgX) <= pieceWidth * 0.5f) {
                            totalX += rowHits.sum()
                            totalCount += rowHits.size
                        }
                    }
                }
                if (totalCount > 0) {
                    val pieceX = (totalX / totalCount).toFloat()
                    val pieceY = (fy - shortEdge * 0.018f).coerceAtLeast(scanTop.toFloat())
                    return Point(pieceX, pieceY)
                }
            }
            fy -= fineStepY
        }
        return null
    }

    private fun collectPieceRowHits(
        pixels: IntArray,
        width: Int,
        y: Int,
        xStart: Int,
        xEnd: Int
    ): List<Int> {
        val rowOffset = y * width
        val hits = ArrayList<Int>(32)
        for (x in xStart..xEnd) {
            if (isPieceColor(pixels[rowOffset + x])) {
                hits.add(x)
            }
        }
        return hits
    }

    private fun findBoard(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        scanBottom: Int,
        pieceX: Float,
        pieceY: Float
    ): BoardPoint? {
        val pieceWidth = max(12, shortEdge / 14)
        val edgeMargin = max(4, width / 50)
        val fineStepY = max(2, shortEdge / 270)
        val minBoardHits = max(4, shortEdge / 120)
        val maxScanY = min(scanBottom, (pieceY - shortEdge * 0.015f).toInt())
            .coerceAtLeast(scanTop + fineStepY)

        // Primary half-screen scan avoids floating music notes / +score above current block
        val primaryRange = if (pieceX < width / 2f) {
            (width / 2) until (width - edgeMargin)
        } else {
            edgeMargin until (width / 2)
        }

        val primaryResult = scanBoardInRange(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            xRange = primaryRange,
            edgeMargin = edgeMargin,
            scanTop = scanTop,
            maxScanY = maxScanY,
            fineStepY = fineStepY,
            minBoardHits = minBoardHits,
            pieceX = pieceX,
            pieceY = pieceY,
            pieceWidth = pieceWidth
        )
        if (primaryResult != null) return primaryResult

        // Fallback if camera didn't recenter target across screen midpoint
        val fallbackRange = if (pieceX < width / 2f) {
            (pieceX.toInt() + pieceWidth).coerceAtMost(width / 2) until (width / 2)
        } else {
            (width / 2) until (pieceX.toInt() - pieceWidth).coerceAtLeast(width / 2)
        }
        if (fallbackRange.first < fallbackRange.last) {
            return scanBoardInRange(
                pixels = pixels,
                width = width,
                height = height,
                shortEdge = shortEdge,
                xRange = fallbackRange,
                edgeMargin = edgeMargin,
                scanTop = scanTop,
                maxScanY = maxScanY,
                fineStepY = fineStepY,
                minBoardHits = minBoardHits,
                pieceX = pieceX,
                pieceY = pieceY,
                pieceWidth = pieceWidth
            )
        }
        return null
    }

    private fun scanBoardInRange(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        xRange: IntRange,
        edgeMargin: Int,
        scanTop: Int,
        maxScanY: Int,
        fineStepY: Int,
        minBoardHits: Int,
        pieceX: Float,
        pieceY: Float,
        pieceWidth: Int
    ): BoardPoint? {
        if (xRange.first >= xRange.last) return null
        val useRightBg = xRange.first >= width / 2
        val bgX = if (useRightBg) width - 1 - edgeMargin else edgeMargin
        val clusterRadius = shortEdge * 0.14f

        var by = scanTop
        while (by < maxScanY) {
            val rowOffset = by * width
            val bgPixel = pixels[rowOffset + bgX]
            val verifyY = min(height - 1, by + 2)
            val verifyOffset = verifyY * width

            val hits = ArrayList<Int>(48)
            for (bx in xRange) {
                if (abs(bx - pieceX) < pieceWidth) continue
                val pixel = pixels[rowOffset + bx]
                if (colorDistance(pixel, bgPixel) > 10) {
                    val pixelBelow = pixels[verifyOffset + bx]
                    if (colorDistance(pixelBelow, bgPixel) > 10) {
                        hits.add(bx)
                    }
                }
            }

            if (hits.size >= minBoardHits) {
                // Use median-centered cluster to stay immune to isolated stray pixels on the same row
                val medianX = hits[hits.size / 2].toFloat()
                var sumX = 0.0
                var count = 0
                for (hx in hits) {
                    if (abs(hx - medianX) <= clusterRadius) {
                        sumX += hx
                        count++
                    }
                }
                if (count >= minBoardHits) {
                    val boardX = (sumX / count).toFloat()
                    val boardTopY = by.toFloat()
                    val boardY = estimateBoardCenterY(
                        pixels = pixels,
                        width = width,
                        height = height,
                        shortEdge = shortEdge,
                        boardX = boardX,
                        boardTopY = by,
                        pieceX = pieceX,
                        pieceY = pieceY
                    )
                    return BoardPoint(boardX, boardY, boardTopY)
                }
            }
            by += fineStepY
        }
        return null
    }

    private fun estimateBoardCenterY(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        boardX: Float,
        boardTopY: Int,
        pieceX: Float,
        pieceY: Float
    ): Float {
        val isoY = pieceY - ISOMETRIC_SLOPE * abs(boardX - pieceX)
        val minCenterY = boardTopY + shortEdge * 0.015f
        val maxCenterY = boardTopY + shortEdge * 0.14f

        val cx = boardX.roundToInt().coerceIn(0, width - 1)
        val probeY = (boardTopY + 3).coerceIn(0, height - 1)
        val surfaceColor = pixels[probeY * width + cx]
        val maxDepth = (shortEdge * 0.22f).toInt()
        var surfaceBottomY = probeY
        val limitY = min(height - 1, boardTopY + maxDepth)
        for (y in probeY..limitY) {
            if (colorDistance(pixels[y * width + cx], surfaceColor) <= 28) {
                surfaceBottomY = y
            } else {
                break
            }
        }
        val surfaceCenterY = (boardTopY + surfaceBottomY) / 2f
        return if (surfaceCenterY in minCenterY..maxCenterY && abs(surfaceCenterY - isoY) < shortEdge * 0.07f) {
            surfaceCenterY
        } else {
            isoY.coerceIn(minCenterY, maxCenterY)
        }
    }

    private fun isPieceColor(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return r in 40..66 && g in 40..66 && b in 78..112
    }

    private fun colorDistance(p1: Int, p2: Int): Int {
        val r1 = (p1 ushr 16) and 0xFF
        val g1 = (p1 ushr 8) and 0xFF
        val b1 = p1 and 0xFF
        val r2 = (p2 ushr 16) and 0xFF
        val g2 = (p2 ushr 8) and 0xFF
        val b2 = p2 and 0xFF
        return abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
    }
}
