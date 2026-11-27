package com.jumphelper.app.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure Kotlin V2.0 pixel recognition engine for WeChat Jump (跳一跳).
 *
 * Upgraded multi-cue geometry architecture (zero OpenCV / native dependencies):
 * - Dual-anchor pawn detection (purple round head + conical base) with tight silhouette masking
 * - Full-width sky profile + dual-edge vertical tracking + gradient-assisted low-contrast edge boost
 * - Physical ground-shadow suppression (0.62..0.89x multiplicative neutral darkening invariant)
 * - Independent left/right corner tracking that solves squares, asymmetric rectangles/benches,
 *   thin round tables, cylinders, multi-color Rubik's cubes, and top roof/cap protrusions
 * - Exact #F4F4F4 white bullseye target-dot lock with 4-side contrast surround validation
 * - 3D isometric back-projection distance fused by geometric confidence
 * - Current standing-block width/center estimation for zero-extra-screenshot closed-loop calibration
 */
object JumpDetector {

    const val DEFAULT_JUMP_FULL_WIDTH_MS = 1700
    const val RECOMMENDED_INITIAL_COEFF_MS = 1740
    const val MIN_PRESS_MS = 200L
    const val MAX_PRESS_MS = 2800L
    const val MIN_PRESS_OFFSET_MS = -120
    const val MAX_PRESS_OFFSET_MS = 120
    const val SMART_ESCORT_CONFIDENCE_THRESHOLD = 0.48f

    // Exact 30 deg isometric camera projection: tan(30 deg) ≈ 0.5774, cos(30 deg) ≈ 0.8660
    private const val ISOMETRIC_COS = 0.8660f
    private const val ISOMETRIC_SLOPE = 0.5774f

    // Exact WeChat Jump parabolic parameters: 2.9167 * t^2 + 26.25 * t = 47.7297 * (dEff / shortEdge)
    private const val PHYSICS_HALF_LINEAR_S = 4.5
    private const val PHYSICS_LINEAR_TERM_SQ = 20.25
    private const val PHYSICS_DISTANCE_COEFF = 16.3645

    enum class DetectionStrategy {
        WHITE_DOT,
        GEOMETRY_FIT,
        FALLBACK_SCAN
    }

    data class PiecePosition(
        val x: Float,
        val y: Float,
        val bitmapWidth: Int,
        val bitmapHeight: Int
    )

    data class DetectionResult(
        val pieceX: Float,
        val pieceY: Float,
        val boardX: Float,
        val boardY: Float,
        val boardTopY: Float,
        val distanceX: Float,
        val shortEdge: Int,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val pressTimeMs: Long,
        val effectiveDistance: Float = distanceX,
        val confidence: Float = 0.85f,
        val strategy: DetectionStrategy = DetectionStrategy.GEOMETRY_FIT,
        val boardWidth: Float = 0f,
        val currentBlockCenterX: Float? = null,
        val currentBlockWidth: Float = 0f,
        val currentBlockConfidence: Float = 0f
    )

    sealed class DetectionOutcome {
        data class Success(val result: DetectionResult) : DetectionOutcome()
        data class Failure(
            val reason: String,
            val isGameOverScreen: Boolean = false
        ) : DetectionOutcome()
    }

    /**
     * Computes vertical scan window [scanTop, scanBottom] matching the active gameplay area.
     */
    fun getScanVerticalBounds(width: Int, height: Int): Pair<Int, Int> {
        val shortEdge = min(width, height).coerceAtLeast(1)
        val scanTop = ((height - shortEdge) / 2 - shortEdge / 30)
            .coerceAtLeast((height * 0.24f).toInt())
        val scanBottom = ((height + shortEdge) / 2)
            .coerceAtMost((height * 0.86f).toInt())
        return scanTop to scanBottom
    }

    /**
     * Fast piece-only lookup used by 1-Tap Smart Rescue so the user only taps the target block.
     */
    fun detectPieceOnly(pixels: IntArray, width: Int, height: Int): PiecePosition? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val shortEdge = min(width, height)
        val (scanTop, scanBottom) = getScanVerticalBounds(width, height)
        if (scanBottom <= scanTop + 24) return null
        val pawn = findPiece(pixels, width, height, shortEdge, scanTop, scanBottom) ?: return null
        return PiecePosition(pawn.x, pawn.y, width, height)
    }

    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        jumpFullWidthMs: Int = DEFAULT_JUMP_FULL_WIDTH_MS,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): DetectionOutcome {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return DetectionOutcome.Failure("截图尺寸异常 (${width}x$height)")
        }

        val shortEdge = min(width, height)
        val (scanTop, scanBottom) = getScanVerticalBounds(width, height)
        if (scanBottom <= scanTop + 24) {
            return DetectionOutcome.Failure("有效扫描区域过小")
        }

        val pawn = findPiece(pixels, width, height, shortEdge, scanTop, scanBottom)
        if (pawn == null) {
            val isGameOver = looksLikeGameOverOverlay(pixels, width, height, scanTop, scanBottom)
            return if (isGameOver) {
                DetectionOutcome.Failure("检测到结算界面，已自动暂停", isGameOverScreen = true)
            } else {
                DetectionOutcome.Failure("未检测到棋子（请确认处于跳一跳游戏界面）", isGameOverScreen = false)
            }
        }

        val edgeInset = max(12, width / 40)
        val fineStepY = max(2, shortEdge / 300)
        val cleanProfileRowY = (scanTop - fineStepY * 2).coerceAtLeast(0)
        val baseDualBg = sampleDualOuterBg(pixels, width, cleanProfileRowY, edgeInset)
        val skyProfile = captureFullWidthSkyProfile(pixels, width, cleanProfileRowY, edgeInset, baseDualBg)

        val board = findBoard(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            scanTop = scanTop,
            scanBottom = scanBottom,
            edgeInset = edgeInset,
            fineStepY = fineStepY,
            pawn = pawn,
            skyProfile = skyProfile,
            baseDualBg = baseDualBg
        ) ?: return DetectionOutcome.Failure("未检测到目标方块（可点「救场」一键起跳）")

        val distanceX = abs(board.x - pawn.x)
        val distanceY = (pawn.y - board.y).coerceAtLeast(0f)
        val effectiveDistance = computeEffectiveDistance(
            dx = distanceX,
            dy = distanceY,
            shortEdge = shortEdge,
            strategy = board.strategy,
            confidence = board.confidence
        )

        val pressTimeMs = calculateAutoPressTimeMs(
            distanceX = effectiveDistance,
            shortEdge = shortEdge,
            jumpFullWidthMs = jumpFullWidthMs,
            pressOffsetMs = pressOffsetMs,
            usePhysicsCurve = usePhysicsCurve
        )

        val curBlock = estimateCurrentBlock(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            scanTop = scanTop,
            edgeInset = edgeInset,
            pawn = pawn,
            targetLeft = board.leftX,
            targetRight = board.rightX,
            skyProfile = skyProfile,
            baseDualBg = baseDualBg
        )

        return DetectionOutcome.Success(
            DetectionResult(
                pieceX = pawn.x,
                pieceY = pawn.y,
                boardX = board.x,
                boardY = board.y,
                boardTopY = board.topY,
                distanceX = distanceX,
                shortEdge = shortEdge,
                bitmapWidth = width,
                bitmapHeight = height,
                pressTimeMs = pressTimeMs,
                effectiveDistance = effectiveDistance,
                confidence = board.confidence,
                strategy = board.strategy,
                boardWidth = board.width,
                currentBlockCenterX = curBlock?.centerX,
                currentBlockWidth = curBlock?.width ?: 0f,
                currentBlockConfidence = curBlock?.confidence ?: 0f
            )
        )
    }

    fun calculateAutoPressTimeMs(
        distanceX: Float,
        shortEdge: Int,
        jumpFullWidthMs: Int,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): Long {
        if (shortEdge <= 0 || distanceX <= 1f) return MIN_PRESS_MS
        val jumpPercent = (distanceX / shortEdge.toFloat()).coerceIn(0f, 1.5f)
        val baseMs = if (usePhysicsCurve) {
            val seconds = -PHYSICS_HALF_LINEAR_S +
                sqrt(PHYSICS_LINEAR_TERM_SQ + PHYSICS_DISTANCE_COEFF * jumpPercent)
            (seconds * 1000.0) * (jumpFullWidthMs / DEFAULT_JUMP_FULL_WIDTH_MS.toDouble())
        } else {
            jumpFullWidthMs * jumpPercent.toDouble()
        }
        val clampedOffset = pressOffsetMs.coerceIn(MIN_PRESS_OFFSET_MS, MAX_PRESS_OFFSET_MS)
        return (baseMs + clampedOffset).roundToLong().coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
    }

    fun calculateManualPressTimeMs(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        screenWidth: Int,
        screenHeight: Int,
        jumpFullWidthMs: Int,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): Long {
        val shortEdge = min(screenWidth, screenHeight).coerceAtLeast(1)
        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        val euclideanDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (euclideanDist <= 1f) return MIN_PRESS_MS

        // Use 3D isometric back-projection when tap vector lies near standard isometric jump cone
        val equivalentDx = if (dx >= shortEdge * 0.04f && dy <= dx * 1.15f) {
            val slope = dy / dx
            if (slope in 0.38f..0.82f) {
                val d3d = sqrt(0.5f * dx * dx + 1.5f * dy * dy)
                d3d * 0.65f + dx * 0.35f
            } else {
                dx
            }
        } else {
            euclideanDist * ISOMETRIC_COS
        }
        return calculateAutoPressTimeMs(
            distanceX = equivalentDx,
            shortEdge = shortEdge,
            jumpFullWidthMs = jumpFullWidthMs,
            pressOffsetMs = pressOffsetMs,
            usePhysicsCurve = usePhysicsCurve
        )
    }

    /**
     * Converts screen displacement (dx, dy) to true 3D isometric jump distance (scaled to equivalent dx).
     * When pawn sits strictly on the 30 deg axis (dy = dx * tan30), d3d == dx identically.
     * When pawn landed off-center on the previous block, 3D back-projection corrects the next jump back to center.
     */
    internal fun computeEffectiveDistance(
        dx: Float,
        dy: Float,
        shortEdge: Int,
        strategy: DetectionStrategy,
        confidence: Float
    ): Float {
        if (dx < shortEdge * 0.05f || dy <= 1f) return dx
        val slope = dy / dx
        // Only apply 3D back-projection inside the physically valid isometric cone
        if (slope !in 0.42f..0.74f) return dx

        val d3d = sqrt(0.5f * dx * dx + 1.5f * dy * dy)
        // Guard against extreme vertical distortion
        if (abs(d3d - dx) > shortEdge * 0.08f) return dx

        val weight3d = when {
            strategy == DetectionStrategy.WHITE_DOT -> 0.80f
            confidence >= 0.80f -> 0.45f
            confidence >= 0.62f -> 0.25f
            else -> 0.0f
        }
        return d3d * weight3d + dx * (1f - weight3d)
    }

    private data class PawnInfo(
        val x: Float,
        val y: Float,
        val topY: Int,
        val bottomY: Int,
        val halfBaseWidth: Float
    )

    private data class BoardPoint(
        val x: Float,
        val y: Float,
        val topY: Float,
        val leftX: Int,
        val rightX: Int,
        val width: Float,
        val strategy: DetectionStrategy,
        val confidence: Float
    )

    private data class CurrentBlockEstimate(
        val centerX: Float,
        val width: Float,
        val confidence: Float
    )

    private data class Rgb(val r: Int, val g: Int, val b: Int)
    private data class DualBg(val left: Rgb, val right: Rgb)
    private data class DeltaRgb(val dr: Int, val dg: Int, val db: Int)
    private data class Span(val left: Int, val right: Int) {
        val width: Int get() = right - left + 1
        val midX: Float get() = (left + right) / 2f
    }

    private fun looksLikeGameOverOverlay(
        pixels: IntArray,
        width: Int,
        height: Int,
        scanTop: Int,
        scanBottom: Int
    ): Boolean {
        // Settlement overlay in WeChat Jump dims the entire canvas into dark gray/charcoal (< 95 luma)
        var darkCount = 0
        var total = 0
        val stepX = max(8, width / 16)
        val stepY = max(8, (scanBottom - scanTop) / 16)
        var y = scanTop
        while (y < scanBottom) {
            val rowOffset = y * width
            var x = stepX
            while (x < width - stepX) {
                val p = pixels[rowOffset + x]
                val luma = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3
                if (luma < 92) darkCount++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && darkCount.toFloat() / total >= 0.58f
    }

    private fun findPiece(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        scanBottom: Int
    ): PawnInfo? {
        val coarseStep = max(2, shortEdge / 44)
        val edgeInset = max(12, width / 40)
        val pieceWidth = max(12, shortEdge / 14)
        val pieceHeight = max(24, shortEdge / 5)
        val fineStepY = max(1, shortEdge / 280)
        val minRowHits = max(5, shortEdge / 95)

        var ny = scanBottom - 1
        while (ny > scanTop) {
            val rowOffset = ny * width
            var nx = edgeInset
            while (nx < width - edgeInset) {
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
                    nx += pieceWidth / 2
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
    ): PawnInfo? {
        val fineStartY = min(height - 1, seedY + coarseStep)
        val fineEndY = max(scanTop, seedY - pieceHeight)
        val xStart = max(0, seedX - pieceWidth)
        val xEnd = min(width - 1, seedX + pieceWidth)
        val maxBaseWidth = (shortEdge * 0.14f).toInt().coerceAtLeast(minRowHits * 2)

        var fy = fineStartY
        while (fy >= fineEndY) {
            val baseSpan = findLongestPieceSpan(pixels, width, fy, xStart, xEnd)
            if (baseSpan != null && baseSpan.width in minRowHits..maxBaseWidth) {
                val baseMidX = baseSpan.midX
                var weightedXSum = baseSpan.midX.toDouble() * baseSpan.width
                var totalWeight = baseSpan.width
                var widestBase = baseSpan.width

                for (step in 1..2) {
                    val rowY = fy - step * fineStepY
                    if (rowY < fineEndY) break
                    val rowSpan = findLongestPieceSpan(pixels, width, rowY, xStart, xEnd)
                    if (rowSpan != null &&
                        rowSpan.width in minRowHits..maxBaseWidth &&
                        abs(rowSpan.midX - baseMidX) <= pieceWidth * 0.45f
                    ) {
                        weightedXSum += rowSpan.midX * rowSpan.width
                        totalWeight += rowSpan.width
                        if (rowSpan.width > widestBase) widestBase = rowSpan.width
                    }
                }

                val baseX = (weightedXSum / totalWeight).toFloat()
                if (hasPawnBodyAbove(pixels, width, scanTop, baseX.roundToInt(), fy, pieceHeight, pieceWidth)) {
                    // Dual-anchor head verification: locate round purple head ~0.13..0.22 * shortEdge above base
                    val headSearchTop = (fy - (shortEdge * 0.225f).toInt()).coerceAtLeast(scanTop)
                    val headSearchBottom = (fy - (shortEdge * 0.13f).toInt()).coerceAtLeast(headSearchTop)
                    val hxLeft = (baseX.roundToInt() - pieceWidth).coerceAtLeast(0)
                    val hxRight = (baseX.roundToInt() + pieceWidth).coerceAtMost(width - 1)
                    val minHeadW = max(4, (shortEdge * 0.022f).toInt())
                    val maxHeadW = max(minHeadW * 2, (shortEdge * 0.085f).toInt())

                    var pawnTopY = (fy - (shortEdge * 0.19f).toInt()).coerceAtLeast(scanTop)
                    val headMids = ArrayList<Float>(16)
                    var foundHeadTop = false
                    var hy = headSearchTop
                    while (hy <= headSearchBottom) {
                        val hSpan = findLongestPieceSpan(pixels, width, hy, hxLeft, hxRight)
                        if (hSpan != null && hSpan.width in minHeadW..maxHeadW) {
                            if (!foundHeadTop) {
                                pawnTopY = hy
                                foundHeadTop = true
                            }
                            headMids.add(hSpan.midX)
                        }
                        hy += max(1, fineStepY)
                    }

                    val finalX = if (headMids.isNotEmpty()) {
                        headMids.sort()
                        val headX = headMids[headMids.size / 2]
                        if (abs(headX - baseX) <= shortEdge * 0.025f) {
                            baseX * 0.65f + headX * 0.35f
                        } else {
                            baseX
                        }
                    } else {
                        baseX
                    }

                    val pieceY = (fy - shortEdge * 0.018f).coerceAtLeast(scanTop.toFloat())
                    return PawnInfo(
                        x = finalX,
                        y = pieceY,
                        topY = pawnTopY,
                        bottomY = fy,
                        halfBaseWidth = (widestBase / 2f).coerceAtLeast(shortEdge * 0.026f)
                    )
                }
            }
            fy -= fineStepY
        }
        return null
    }

    private fun findLongestPieceSpan(
        pixels: IntArray,
        width: Int,
        y: Int,
        xStart: Int,
        xEnd: Int
    ): Span? {
        val rowOffset = y * width
        var bestLeft = -1
        var bestRight = -2
        var curLeft = -1
        var lastHit = -1
        val gapTolerance = 2

        for (x in xStart..xEnd) {
            if (isPieceColor(pixels[rowOffset + x])) {
                if (curLeft == -1) {
                    curLeft = x
                } else if (x - lastHit > gapTolerance + 1) {
                    if (lastHit - curLeft > bestRight - bestLeft) {
                        bestLeft = curLeft
                        bestRight = lastHit
                    }
                    curLeft = x
                }
                lastHit = x
            }
        }
        if (curLeft != -1 && lastHit - curLeft > bestRight - bestLeft) {
            bestLeft = curLeft
            bestRight = lastHit
        }
        return if (bestLeft >= 0) Span(bestLeft, bestRight) else null
    }

    private fun hasPawnBodyAbove(
        pixels: IntArray,
        width: Int,
        scanTop: Int,
        centerX: Int,
        baseY: Int,
        pieceHeight: Int,
        pieceWidth: Int
    ): Boolean {
        val checkFractions = floatArrayOf(0.25f, 0.45f, 0.65f)
        val halfSearch = max(6, pieceWidth / 2)
        val xLeft = (centerX - halfSearch).coerceAtLeast(0)
        val xRight = (centerX + halfSearch).coerceAtMost(width - 1)
        var validLevels = 0

        for (frac in checkFractions) {
            val checkY = baseY - (pieceHeight * frac).toInt()
            if (checkY < scanTop) continue
            val rowOffset = checkY * width
            var hits = 0
            for (x in xLeft..xRight) {
                if (isPieceColor(pixels[rowOffset + x])) {
                    hits++
                }
            }
            if (hits >= 3) {
                validLevels++
            }
        }
        return validLevels >= 2
    }

    private fun findBoard(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        scanBottom: Int,
        edgeInset: Int,
        fineStepY: Int,
        pawn: PawnInfo,
        skyProfile: IntArray,
        baseDualBg: DualBg
    ): BoardPoint? {
        val pieceExclusionRadius = max(12, (shortEdge * 0.055f).toInt())
        val minBoardHits = max(5, shortEdge / 120)
        val maxScanY = min(scanBottom, (pawn.y - shortEdge * 0.012f).toInt())
            .coerceAtLeast(scanTop + fineStepY)

        // Primary half-screen scan avoids floating music notes / +score above current block
        val primaryRange = if (pawn.x < width / 2f) {
            (width / 2) until (width - edgeInset)
        } else {
            edgeInset until (width / 2)
        }
        // Fallback when target sits on same half-screen as pawn (high-score tight jumps)
        val fallbackRange = if (pawn.x < width / 2f) {
            (pawn.x.toInt() + pieceExclusionRadius).coerceAtMost(width / 2) until (width / 2)
        } else {
            (width / 2) until (pawn.x.toInt() - pieceExclusionRadius).coerceAtLeast(width / 2)
        }

        // Pass 1: Strict ground-shadow suppression (eliminates lower-left cast shadow interference)
        val primaryClean = scanBoardInRange(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            xRange = primaryRange,
            edgeInset = edgeInset,
            scanTop = scanTop,
            maxScanY = maxScanY,
            fineStepY = fineStepY,
            minBoardHits = minBoardHits,
            pawn = pawn,
            pieceExclusionRadius = pieceExclusionRadius,
            skyProfile = skyProfile,
            baseDualBg = baseDualBg,
            suppressShadow = true,
            isFallbackPass = false
        )
        if (primaryClean != null) return primaryClean

        if (fallbackRange.first < fallbackRange.last) {
            val fallbackClean = scanBoardInRange(
                pixels = pixels,
                width = width,
                height = height,
                shortEdge = shortEdge,
                xRange = fallbackRange,
                edgeInset = edgeInset,
                scanTop = scanTop,
                maxScanY = maxScanY,
                fineStepY = fineStepY,
                minBoardHits = minBoardHits,
                pawn = pawn,
                pieceExclusionRadius = pieceExclusionRadius,
                skyProfile = skyProfile,
                baseDualBg = baseDualBg,
                suppressShadow = true,
                isFallbackPass = false
            )
            if (fallbackClean != null) return fallbackClean
        }

        // Pass 2: Rare fallback for a gray block whose top face identically matches 0.70x sky tint
        val primaryAllowShadow = scanBoardInRange(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            xRange = primaryRange,
            edgeInset = edgeInset,
            scanTop = scanTop,
            maxScanY = maxScanY,
            fineStepY = fineStepY,
            minBoardHits = minBoardHits,
            pawn = pawn,
            pieceExclusionRadius = pieceExclusionRadius,
            skyProfile = skyProfile,
            baseDualBg = baseDualBg,
            suppressShadow = false,
            isFallbackPass = true
        )
        if (primaryAllowShadow != null) return primaryAllowShadow

        if (fallbackRange.first < fallbackRange.last) {
            return scanBoardInRange(
                pixels = pixels,
                width = width,
                height = height,
                shortEdge = shortEdge,
                xRange = fallbackRange,
                edgeInset = edgeInset,
                scanTop = scanTop,
                maxScanY = maxScanY,
                fineStepY = fineStepY,
                minBoardHits = minBoardHits,
                pawn = pawn,
                pieceExclusionRadius = pieceExclusionRadius,
                skyProfile = skyProfile,
                baseDualBg = baseDualBg,
                suppressShadow = false,
                isFallbackPass = true
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
        edgeInset: Int,
        scanTop: Int,
        maxScanY: Int,
        fineStepY: Int,
        minBoardHits: Int,
        pawn: PawnInfo,
        pieceExclusionRadius: Int,
        skyProfile: IntArray,
        baseDualBg: DualBg,
        suppressShadow: Boolean,
        isFallbackPass: Boolean
    ): BoardPoint? {
        if (xRange.first >= xRange.last) return null
        val diffThreshold = 14
        val minBlockWidth = max(20, shortEdge / 30)
        val clusterGap = max(4, shortEdge / 240)
        val pawnMaskTopY = pawn.topY - 6

        var by = scanTop
        while (by < maxScanY) {
            val rowDelta = sampleRowBgDelta(pixels, width, by, edgeInset, baseDualBg)
            val verifyY1 = min(height - 1, by + fineStepY)
            val verifyY2 = min(height - 1, by + fineStepY * 2)
            val v1Delta = sampleRowBgDelta(pixels, width, verifyY1, edgeInset, baseDualBg)
            val v2Delta = sampleRowBgDelta(pixels, width, verifyY2, edgeInset, baseDualBg)
            val nearPawnRows = by >= pawnMaskTopY

            val clusters = ArrayList<Span>(4)
            var curLeft = -1
            var lastHit = -1
            var curHits = 0

            for (bx in xRange) {
                if (nearPawnRows && abs(bx - pawn.x) < pieceExclusionRadius) continue
                if (isForegroundPixel(pixels, width, height, bx, by, skyProfile, rowDelta, diffThreshold, suppressShadow) &&
                    isForegroundPixel(pixels, width, height, bx, verifyY1, skyProfile, v1Delta, diffThreshold, suppressShadow) &&
                    isForegroundPixel(pixels, width, height, bx, verifyY2, skyProfile, v2Delta, diffThreshold, suppressShadow)
                ) {
                    if (curLeft == -1) {
                        curLeft = bx
                        curHits = 1
                    } else if (bx - lastHit <= clusterGap) {
                        curHits++
                    } else {
                        if (curHits >= minBoardHits) {
                            clusters.add(Span(curLeft, lastHit))
                        }
                        curLeft = bx
                        curHits = 1
                    }
                    lastHit = bx
                }
            }
            if (curLeft != -1 && curHits >= minBoardHits) {
                clusters.add(Span(curLeft, lastHit))
            }

            for (cluster in clusters) {
                val refined = refineBoardTopFace(
                    pixels = pixels,
                    width = width,
                    height = height,
                    shortEdge = shortEdge,
                    edgeInset = edgeInset,
                    skyProfile = skyProfile,
                    baseDualBg = baseDualBg,
                    boardTopY = by,
                    seedMidX = cluster.midX,
                    diffThreshold = diffThreshold,
                    minBlockWidth = minBlockWidth,
                    pawn = pawn,
                    suppressShadow = suppressShadow,
                    isFallbackPass = isFallbackPass
                )
                if (refined != null) {
                    return refined
                }
            }
            by += fineStepY
        }
        return null
    }

    private fun refineBoardTopFace(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        edgeInset: Int,
        skyProfile: IntArray,
        baseDualBg: DualBg,
        boardTopY: Int,
        seedMidX: Float,
        diffThreshold: Int,
        minBlockWidth: Int,
        pawn: PawnInfo,
        suppressShadow: Boolean,
        isFallbackPass: Boolean
    ): BoardPoint? {
        val trackStep = max(2, shortEdge / 320)
        val maxDepth = (shortEdge * 0.22f).toInt().coerceAtLeast(48)
        val limitY = min(height - 1, boardTopY + maxDepth)
        val gapTolerance = max(3, shortEdge / 260)
        val maxEdgeJump = max(20, (shortEdge * 0.085f).toInt())
        val narrowProtrusionLimit = (shortEdge * 0.11f).toInt()
        val maxValidBlockWidth = (shortEdge * 0.48f).toInt()

        // Tight contour-aware pawn clearance: only constrain horizontally on rows where pawn actually exists!
        val pawnMaskTopY = pawn.topY - 6
        val pawnSafeGap = max(10, (pawn.halfBaseWidth * 1.15f).toInt())

        // Verify whether the block's own top surface happens to match the 0.70x shadow profile
        val probeY = min(height - 1, boardTopY + max(3, trackStep * 2))
        val probeX = seedMidX.roundToInt().coerceIn(edgeInset, width - 1 - edgeInset)
        val probeDelta = sampleRowBgDelta(pixels, width, probeY, edgeInset, baseDualBg)
        val probeBg = expectedSkyPixel(skyProfile, probeX, probeDelta)
        val effSuppressShadow = suppressShadow && !isGroundShadow(pixels[probeY * width + probeX], probeBg)

        val trackedRows = ArrayList<Pair<Int, Span>>(48)
        var maxSpanW = 0
        var minLeftX = Int.MAX_VALUE
        var maxRightX = Int.MIN_VALUE
        var flatLeftCount = 0
        var flatRightCount = 0
        var effectiveTopY = boardTopY
        var trackCenter = seedMidX.roundToInt().coerceIn(edgeInset, width - 1 - edgeInset)

        var y = boardTopY
        while (y <= limitY) {
            val rowDelta = sampleRowBgDelta(pixels, width, y, edgeInset, baseDualBg)
            val nearPawn = y >= pawnMaskTopY
            val minBoundX = if (nearPawn && seedMidX >= pawn.x) {
                max(edgeInset, (pawn.x + pawnSafeGap).toInt())
            } else {
                edgeInset
            }
            val maxBoundX = if (nearPawn && seedMidX < pawn.x) {
                min(width - 1 - edgeInset, (pawn.x - pawnSafeGap).toInt())
            } else {
                width - 1 - edgeInset
            }
            if (maxBoundX <= minBoundX + 8) break

            val span = measureConnectedForegroundSpan(
                pixels = pixels,
                width = width,
                height = height,
                y = y,
                centerSeedX = trackCenter.coerceIn(minBoundX, maxBoundX),
                minX = minBoundX,
                maxX = maxBoundX,
                skyProfile = skyProfile,
                delta = rowDelta,
                diffThreshold = diffThreshold,
                gapTolerance = gapTolerance,
                pawn = pawn,
                nearPawn = nearPawn,
                suppressShadow = effSuppressShadow
            ) ?: break

            if (trackedRows.isNotEmpty()) {
                val prev = trackedRows.last().second
                val leftExpansion = prev.left - span.left
                val rightExpansion = span.right - prev.right
                val suddenJump = leftExpansion > maxEdgeJump || rightExpansion > maxEdgeJump

                if (suddenJump) {
                    // Protrusion / bottle-cap / shop-sign step-down: if we were only on a narrow top ornament
                    // and just stepped onto a clean main landing deck, reset top tracking onto the real deck!
                    if (maxSpanW < narrowProtrusionLimit && span.width <= maxValidBlockWidth) {
                        trackedRows.clear()
                        effectiveTopY = y
                        maxSpanW = 0
                        minLeftX = Int.MAX_VALUE
                        maxRightX = Int.MIN_VALUE
                        flatLeftCount = 0
                        flatRightCount = 0
                    } else {
                        break
                    }
                } else if (span.width < maxSpanW * 0.38f) {
                    break
                }
            }

            if (span.width > maxValidBlockWidth && trackedRows.size >= 3) {
                break
            }

            trackedRows.add(y to span)
            if (span.width > maxSpanW) {
                maxSpanW = span.width
            }

            if (span.left < minLeftX) {
                minLeftX = span.left
                flatLeftCount = 0
            } else if (y - effectiveTopY > shortEdge * 0.022f) {
                flatLeftCount++
            }

            if (span.right > maxRightX) {
                maxRightX = span.right
                flatRightCount = 0
            } else if (y - effectiveTopY > shortEdge * 0.022f) {
                flatRightCount++
            }

            // Never stop early while still on a narrow top protrusion (cap/signboard); once on the main deck,
            // wait 10 flat steps on BOTH sides so asymmetric benches finish expanding & ellipses reveal rim retreat.
            if (maxSpanW >= narrowProtrusionLimit &&
                flatLeftCount >= 10 &&
                flatRightCount >= 10 &&
                y - effectiveTopY > shortEdge * 0.045f
            ) {
                break
            }

            trackCenter = span.midX.roundToInt().coerceIn(minBoundX, maxBoundX)
            y += trackStep
        }

        if (trackedRows.size < 4 || maxSpanW < minBlockWidth) {
            return null
        }

        // Compute independent left & right corners (exact center for squares, asymmetric rectangles, & ellipses!)
        var trueMinLeft = Int.MAX_VALUE
        var trueMaxRight = Int.MIN_VALUE
        for ((_, sp) in trackedRows) {
            if (sp.left < trueMinLeft) trueMinLeft = sp.left
            if (sp.right > trueMaxRight) trueMaxRight = sp.right
        }
        val blockWidth = (trueMaxRight - trueMinLeft + 1).toFloat()
        if (blockWidth < minBlockWidth) return null

        val lastSpan = trackedRows.last().second
        val leftRetreats = lastSpan.left >= trueMinLeft + 2
        val rightRetreats = lastSpan.right <= trueMaxRight - 2

        val leftCornerY = findCornerY(
            trackedRows = trackedRows,
            isMatch = { it.left <= trueMinLeft + 1 },
            edgeRetreats = leftRetreats,
            maxPlateauSpanY = (shortEdge * 0.045f).toInt()
        )
        val rightCornerY = findCornerY(
            trackedRows = trackedRows,
            isMatch = { it.right >= trueMaxRight - 1 },
            edgeRetreats = rightRetreats,
            maxPlateauSpanY = (shortEdge * 0.045f).toInt()
        )

        val geoCenterX = (trueMinLeft + trueMaxRight) / 2f
        val geoCenterY = (leftCornerY + rightCornerY) / 2f

        // Priority #1: Search for exact #F4F4F4 white bullseye target dot inside the candidate block!
        val estHalfHeight = max(geoCenterY - effectiveTopY, shortEdge * 0.035f)
        val dotSearchBottom = min(height - 1, (effectiveTopY + estHalfHeight * 2.1f).toInt())
        val whiteDot = findWhiteTargetDot(
            pixels = pixels,
            width = width,
            height = height,
            shortEdge = shortEdge,
            leftX = trueMinLeft,
            rightX = trueMaxRight,
            topY = effectiveTopY,
            bottomY = dotSearchBottom
        )
        if (whiteDot != null) {
            return BoardPoint(
                x = whiteDot.first,
                y = whiteDot.second,
                topY = effectiveTopY.toFloat(),
                leftX = trueMinLeft,
                rightX = trueMaxRight,
                width = blockWidth,
                strategy = DetectionStrategy.WHITE_DOT,
                confidence = 0.98f
            )
        }

        // Cross-check geoCenterY against isometric projection & evaluate detection confidence
        val isoY = pawn.y - ISOMETRIC_SLOPE * abs(geoCenterX - pawn.x)
        val minCenterY = effectiveTopY + shortEdge * 0.014f
        val maxCenterY = effectiveTopY + shortEdge * 0.18f
        val isoDelta = abs(geoCenterY - isoY)

        val finalY: Float
        var confidence: Float
        if (geoCenterY in minCenterY..maxCenterY && isoDelta <= shortEdge * 0.125f) {
            finalY = geoCenterY
            confidence = if (isoDelta <= shortEdge * 0.085f) 0.89f else 0.81f
        } else {
            val surfaceY = estimateBoardCenterY(
                pixels = pixels,
                width = width,
                height = height,
                shortEdge = shortEdge,
                boardX = geoCenterX,
                boardTopY = effectiveTopY,
                pieceX = pawn.x,
                pieceY = pawn.y,
                skyProfile = skyProfile,
                baseDualBg = baseDualBg,
                edgeInset = edgeInset
            )
            finalY = surfaceY
            confidence = if (abs(surfaceY - isoY) <= shortEdge * 0.065f) 0.74f else 0.52f
        }

        // Confidence penalties for extreme geometries / potential edge clipping / fallback shadow pass
        val hitLeftEdge = trueMinLeft <= edgeInset + 3
        val hitRightEdge = trueMaxRight >= width - 1 - edgeInset - 3
        if (hitLeftEdge || hitRightEdge) {
            confidence -= 0.18f
        }
        if (isFallbackPass) {
            confidence -= 0.16f
        }
        val aspectRatio = blockWidth / max(1f, (finalY - effectiveTopY) * 2f)
        if (aspectRatio < 0.75f || aspectRatio > 3.8f) {
            confidence -= 0.20f
        }
        val dxToPawn = abs(geoCenterX - pawn.x)
        if (dxToPawn < shortEdge * 0.07f || dxToPawn > shortEdge * 0.85f) {
            confidence -= 0.22f
        }
        if (isoDelta > shortEdge * 0.11f) {
            confidence -= 0.18f
        }

        val strategy = if (isFallbackPass || confidence < 0.55f) {
            DetectionStrategy.FALLBACK_SCAN
        } else {
            DetectionStrategy.GEOMETRY_FIT
        }

        return BoardPoint(
            x = geoCenterX,
            y = finalY,
            topY = effectiveTopY.toFloat(),
            leftX = trueMinLeft,
            rightX = trueMaxRight,
            width = blockWidth,
            strategy = strategy,
            confidence = confidence.coerceIn(0.15f, 0.94f)
        )
    }

    private fun findCornerY(
        trackedRows: List<Pair<Int, Span>>,
        isMatch: (Span) -> Boolean,
        edgeRetreats: Boolean,
        maxPlateauSpanY: Int
    ): Float {
        var firstY = -1
        var plateauEndY = -1
        for ((rowY, span) in trackedRows) {
            if (isMatch(span)) {
                if (firstY == -1) {
                    firstY = rowY
                    plateauEndY = rowY
                } else if (rowY - firstY <= maxPlateauSpanY) {
                    plateauEndY = rowY
                } else {
                    break
                }
            } else if (firstY != -1) {
                break
            }
        }
        if (firstY == -1) return trackedRows.last().first.toFloat()
        // For thin ellipses/round tables whose edge retreats back inward, midpoint of the rim plateau is true cy.
        // For cubes & tall cylinders whose vertical side wall stays straight down, firstY is the sharp top corner!
        return if (edgeRetreats) {
            (firstY + plateauEndY) / 2f
        } else {
            firstY.toFloat()
        }
    }

    /**
     * Detects WeChat Jump's exact #F4F4F4 elliptical center target dot inside the candidate block.
     * Verified empirically: neutral bright white (234..254, chroma <= 12), width ~3.4% of shortEdge,
     * height ~1.85% of shortEdge (aspect ~1.83), elliptical fill ~0.78, surrounded by contrasting surface.
     */
    private fun findWhiteTargetDot(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        leftX: Int,
        rightX: Int,
        topY: Int,
        bottomY: Int
    ): Pair<Float, Float>? {
        val bw = rightX - leftX + 1
        val bh = bottomY - topY + 1
        val sx0 = (leftX + (bw * 0.14f).toInt()).coerceIn(2, width - 3)
        val sx1 = (rightX - (bw * 0.14f).toInt()).coerceIn(sx0, width - 3)
        val sy0 = (topY + (bh * 0.10f).toInt()).coerceIn(2, height - 3)
        val sy1 = (bottomY - (bh * 0.10f).toInt()).coerceIn(sy0, height - 3)
        val regionW = sx1 - sx0 + 1
        val regionH = sy1 - sy0 + 1
        if (regionW < 10 || regionH < 6) return null

        val mask = BooleanArray(regionW * regionH)
        var whiteCount = 0
        for (y in sy0..sy1) {
            val rowOff = y * width
            val mOff = (y - sy0) * regionW
            for (x in sx0..sx1) {
                val p = pixels[rowOff + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val minC = min(r, min(g, b))
                val maxC = max(r, max(g, b))
                if (minC >= 234 && maxC <= 254 && (maxC - minC) <= 12) {
                    mask[mOff + (x - sx0)] = true
                    whiteCount++
                }
            }
        }
        val minDotPixels = max(12, ((shortEdge * 0.022f) * (shortEdge * 0.011f) * 0.55f).toInt())
        if (whiteCount < minDotPixels) return null

        val visited = BooleanArray(regionW * regionH)
        val queue = IntArray(regionW * regionH)
        val minDw = shortEdge * 0.020f
        val maxDw = shortEdge * 0.050f
        val minDh = shortEdge * 0.010f
        val maxDh = shortEdge * 0.030f

        var bestScore = Float.MAX_VALUE
        var bestX = 0f
        var bestY = 0f
        var found = false

        for (idx in mask.indices) {
            if (!mask[idx] || visited[idx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = idx
            visited[idx] = true

            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0L
            var sumY = 0L
            var count = 0

            while (head < tail) {
                val cur = queue[head++]
                val ly = cur / regionW
                val lx = cur - ly * regionW
                val gx = sx0 + lx
                val gy = sy0 + ly
                if (gx < minX) minX = gx
                if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy
                if (gy > maxY) maxY = gy
                sumX += gx
                sumY += gy
                count++

                if (lx > 0) {
                    val nb = cur - 1
                    if (mask[nb] && !visited[nb]) {
                        visited[nb] = true
                        queue[tail++] = nb
                    }
                }
                if (lx + 1 < regionW) {
                    val nb = cur + 1
                    if (mask[nb] && !visited[nb]) {
                        visited[nb] = true
                        queue[tail++] = nb
                    }
                }
                if (ly > 0) {
                    val nb = cur - regionW
                    if (mask[nb] && !visited[nb]) {
                        visited[nb] = true
                        queue[tail++] = nb
                    }
                }
                if (ly + 1 < regionH) {
                    val nb = cur + regionW
                    if (mask[nb] && !visited[nb]) {
                        visited[nb] = true
                        queue[tail++] = nb
                    }
                }
            }

            if (count < minDotPixels) continue
            val dw = (maxX - minX + 1).toFloat()
            val dh = (maxY - minY + 1).toFloat()
            if (dw !in minDw..maxDw || dh !in minDh..maxDh) continue

            val aspect = dw / dh.coerceAtLeast(1f)
            val fill = count / (dw * dh).coerceAtLeast(1f)
            if (aspect !in 1.30f..2.50f || fill !in 0.56f..0.96f) continue

            val cx = sumX.toFloat() / count
            val cy = sumY.toFloat() / count
            val dotColor = pixels[cy.roundToInt().coerceIn(0, height - 1) * width + cx.roundToInt().coerceIn(0, width - 1)]

            // 4-side contrast surround check rejects pure white blocks / specular patches
            val padX = (dw * 0.65f + 3f).toInt()
            val padY = (dh * 0.65f + 3f).toInt()
            val probeCoords = arrayOf(
                (cx.roundToInt() - padX) to cy.roundToInt(),
                (cx.roundToInt() + padX) to cy.roundToInt(),
                cx.roundToInt() to (cy.roundToInt() - padY),
                cx.roundToInt() to (cy.roundToInt() + padY)
            )
            var contrastSides = 0
            for ((pxX, pxY) in probeCoords) {
                if (pxX in 0 until width && pxY in 0 until height) {
                    if (colorDistance(pixels[pxY * width + pxX], dotColor) >= 22) {
                        contrastSides++
                    }
                }
            }
            if (contrastSides >= 3) {
                val score = abs(aspect - 1.83f) + abs(fill - 0.785f)
                if (score < bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                    found = true
                }
            }
        }
        return if (found) bestX to bestY else null
    }

    /**
     * Estimates the top-face center and width of the block the pawn is CURRENTLY standing on.
     * Used by AutoCalibrator (zero extra screenshots!) to measure previous jump landing error.
     */
    private fun estimateCurrentBlock(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        edgeInset: Int,
        pawn: PawnInfo,
        targetLeft: Int,
        targetRight: Int,
        skyProfile: IntArray,
        baseDualBg: DualBg
    ): CurrentBlockEstimate? {
        val cx = pawn.x.roundToInt().coerceIn(edgeInset, width - 1 - edgeInset)
        val probeY = (pawn.bottomY + 4).coerceIn(0, height - 1)
        val probeDelta = sampleRowBgDelta(pixels, width, probeY, edgeInset, baseDualBg)
        val probeBg = expectedSkyPixel(skyProfile, cx, probeDelta)
        val suppressShadow = !isGroundShadow(pixels[probeY * width + cx], probeBg)

        val bandTop = (pawn.y - shortEdge * 0.04f).toInt().coerceAtLeast(scanTop)
        val bandBottom = (pawn.y + shortEdge * 0.05f).toInt().coerceAtMost(height - 1)
        val stepY = max(2, shortEdge / 300)
        val maxHalfSpan = (shortEdge * 0.25f).toInt()
        val minW = (shortEdge * 0.13f).toInt()
        val maxW = (shortEdge * 0.45f).toInt()
        val gapTol = max(3, shortEdge / 260)
        val pawnBridgeRadius = (shortEdge * 0.055f)

        val validSpans = ArrayList<Span>(16)
        var maxSpanW = 0

        var y = bandTop
        while (y <= bandBottom) {
            val rowDelta = sampleRowBgDelta(pixels, width, y, edgeInset, baseDualBg)
            val rowOffset = y * width

            var left = cx
            var gap = 0
            val leftLimit = max(edgeInset, cx - maxHalfSpan)
            var x = cx - 1
            while (x >= leftLimit) {
                val p = pixels[rowOffset + x]
                val isBridge = abs(x - pawn.x) <= pawnBridgeRadius && isPieceColor(p)
                if (isBridge || isForegroundPixel(pixels, width, height, x, y, skyProfile, rowDelta, 15, suppressShadow)) {
                    left = x
                    gap = 0
                } else {
                    gap++
                    if (gap > gapTol) break
                }
                x--
            }

            var right = cx
            gap = 0
            val rightLimit = min(width - 1 - edgeInset, cx + maxHalfSpan)
            x = cx + 1
            while (x <= rightLimit) {
                val p = pixels[rowOffset + x]
                val isBridge = abs(x - pawn.x) <= pawnBridgeRadius && isPieceColor(p)
                if (isBridge || isForegroundPixel(pixels, width, height, x, y, skyProfile, rowDelta, 15, suppressShadow)) {
                    right = x
                    gap = 0
                } else {
                    gap++
                    if (gap > gapTol) break
                }
                x++
            }

            val sw = right - left + 1
            val touchesBorder = left <= edgeInset + 4 || right >= width - 1 - edgeInset - 4
            // Guard against direct horizontal overlap with the detected next target block
            val overlapsTarget = (targetLeft in (left + 1) until right) || (targetRight in (left + 1) until right)
            if (sw in minW..maxW && !touchesBorder && !overlapsTarget) {
                validSpans.add(Span(left, right))
                if (sw > maxSpanW) maxSpanW = sw
            }
            y += stepY
        }

        if (validSpans.size < 4 || maxSpanW < minW) return null

        val wideThreshold = (maxSpanW * 0.94f).toInt()
        val mids = ArrayList<Float>(validSpans.size)
        for (sp in validSpans) {
            if (sp.width >= wideThreshold) {
                mids.add(sp.midX)
            }
        }
        if (mids.isEmpty()) return null
        mids.sort()
        val centerX = mids[mids.size / 2]
        val spread = mids.last() - mids.first()
        val normSpread = spread / shortEdge.toFloat()
        val conf = when {
            normSpread <= 0.011f -> 0.90f
            normSpread <= 0.020f -> 0.76f
            else -> 0.45f
        }
        return CurrentBlockEstimate(centerX = centerX, width = maxSpanW.toFloat(), confidence = conf)
    }

    private fun measureConnectedForegroundSpan(
        pixels: IntArray,
        width: Int,
        height: Int,
        y: Int,
        centerSeedX: Int,
        minX: Int,
        maxX: Int,
        skyProfile: IntArray,
        delta: DeltaRgb,
        diffThreshold: Int,
        gapTolerance: Int,
        pawn: PawnInfo,
        nearPawn: Boolean,
        suppressShadow: Boolean
    ): Span? {
        val rowOffset = y * width
        val pawnHalfMask = max(10f, pawn.halfBaseWidth * 1.35f)
        fun isBlockedPawnPixel(pxX: Int, argb: Int): Boolean {
            return nearPawn &&
                abs(pxX - pawn.x) <= pawnHalfMask &&
                isPieceColor(argb)
        }

        var anchorX = -1
        for (deltaX in 0..gapTolerance * 2) {
            val rx = (centerSeedX + deltaX).coerceAtMost(maxX)
            val rp = pixels[rowOffset + rx]
            if (!isBlockedPawnPixel(rx, rp) &&
                isForegroundPixel(pixels, width, height, rx, y, skyProfile, delta, diffThreshold, suppressShadow)
            ) {
                anchorX = rx
                break
            }
            val lx = (centerSeedX - deltaX).coerceAtLeast(minX)
            val lp = pixels[rowOffset + lx]
            if (!isBlockedPawnPixel(lx, lp) &&
                isForegroundPixel(pixels, width, height, lx, y, skyProfile, delta, diffThreshold, suppressShadow)
            ) {
                anchorX = lx
                break
            }
        }
        if (anchorX == -1) return null

        var left = anchorX
        var x = anchorX - 1
        var gap = 0
        while (x >= minX) {
            val p = pixels[rowOffset + x]
            if (isBlockedPawnPixel(x, p)) break
            if (isForegroundPixel(pixels, width, height, x, y, skyProfile, delta, diffThreshold, suppressShadow)) {
                left = x
                gap = 0
            } else {
                gap++
                if (gap > gapTolerance) break
            }
            x--
        }

        var right = anchorX
        x = anchorX + 1
        gap = 0
        while (x <= maxX) {
            val p = pixels[rowOffset + x]
            if (isBlockedPawnPixel(x, p)) break
            if (isForegroundPixel(pixels, width, height, x, y, skyProfile, delta, diffThreshold, suppressShadow)) {
                right = x
                gap = 0
            } else {
                gap++
                if (gap > gapTolerance) break
            }
            x++
        }

        return Span(left, right)
    }

    /**
     * Multi-cue foreground test:
     * 1. Sky-profile color difference (> diffThreshold), OR local color-gradient boost for ultra-low-contrast white rims.
     * 2. Physical ground-shadow rejection (0.62..0.89x multiplicative neutral darkening invariant).
     */
    private fun isForegroundPixel(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        skyProfile: IntArray,
        delta: DeltaRgb,
        diffThreshold: Int,
        suppressShadow: Boolean
    ): Boolean {
        val p = pixels[y * width + x]
        val bg = expectedSkyPixel(skyProfile, x, delta)
        val dist = colorDistance(p, bg)
        if (dist <= diffThreshold) {
            // Gradient-assisted low-contrast edge boost (catches faint white/pastel rims on bright skies)
            if (dist > 9 && x in 1 until (width - 1) && y in 1 until (height - 1)) {
                val grad = colorDistance(pixels[y * width + x + 1], pixels[y * width + x - 1]) +
                    colorDistance(pixels[(y + 1) * width + x], pixels[(y - 1) * width + x])
                if (grad < 22) return false
            } else {
                return false
            }
        }
        if (suppressShadow && isGroundShadow(p, bg)) {
            return false
        }
        return true
    }

    /**
     * WeChat Jump renders cast ground shadows as a ~0.30-alpha black shadow plane over the sky background.
     * Empirically verified across blue-gray and pink themes: R/G/B ratios sit tightly in [0.62..0.89]
     * with channel spread <= 0.038 (near-zero chromatic shift relative to sky background).
     */
    internal fun isGroundShadow(pixel: Int, bgPixel: Int): Boolean {
        val br = ((bgPixel ushr 16) and 0xFF).coerceAtLeast(1)
        val bg = ((bgPixel ushr 8) and 0xFF).coerceAtLeast(1)
        val bb = (bgPixel and 0xFF).coerceAtLeast(1)
        // Skip shadow heuristic on very dark night skies where division ratios become noisy
        if (br < 60 && bg < 60 && bb < 60) return false

        val rr = ((pixel ushr 16) and 0xFF) / br.toFloat()
        val gr = ((pixel ushr 8) and 0xFF) / bg.toFloat()
        val bRatio = (pixel and 0xFF) / bb.toFloat()

        val minR = min(rr, min(gr, bRatio))
        val maxR = max(rr, max(gr, bRatio))
        return minR >= 0.62f && maxR <= 0.89f && (maxR - minR) <= 0.038f
    }

    private fun sampleStripMedian(pixels: IntArray, width: Int, y: Int, stripStart: Int, stripEnd: Int): Rgb {
        val rowOffset = y * width
        val samplesR = IntArray(5)
        val samplesG = IntArray(5)
        val samplesB = IntArray(5)
        val span = (stripEnd - stripStart).coerceAtLeast(1)
        for (i in 0 until 5) {
            val sx = (stripStart + (span * i) / 4).coerceIn(0, width - 1)
            val argb = pixels[rowOffset + sx]
            samplesR[i] = (argb ushr 16) and 0xFF
            samplesG[i] = (argb ushr 8) and 0xFF
            samplesB[i] = argb and 0xFF
        }
        samplesR.sort()
        samplesG.sort()
        samplesB.sort()
        return Rgb(samplesR[2], samplesG[2], samplesB[2])
    }

    private fun sampleDualOuterBg(pixels: IntArray, width: Int, y: Int, edgeInset: Int): DualBg {
        val stripLen = 20.coerceAtMost((width / 8).coerceAtLeast(4))
        val leftStart = edgeInset.coerceIn(0, width - 1)
        val leftEnd = (leftStart + stripLen).coerceAtMost(width - 1)
        val rightEnd = (width - 1 - edgeInset).coerceAtLeast(0)
        val rightStart = (rightEnd - stripLen).coerceAtLeast(0)
        return DualBg(
            left = sampleStripMedian(pixels, width, y, leftStart, leftEnd),
            right = sampleStripMedian(pixels, width, y, rightStart, rightEnd)
        )
    }

    private fun sampleRowBgDelta(
        pixels: IntArray,
        width: Int,
        y: Int,
        edgeInset: Int,
        baseDualBg: DualBg
    ): DeltaRgb {
        val cur = sampleDualOuterBg(pixels, width, y, edgeInset)
        val dlR = cur.left.r - baseDualBg.left.r
        val dlG = cur.left.g - baseDualBg.left.g
        val dlB = cur.left.b - baseDualBg.left.b
        val distL = abs(dlR) + abs(dlG) + abs(dlB)

        val drR = cur.right.r - baseDualBg.right.r
        val drG = cur.right.g - baseDualBg.right.g
        val drB = cur.right.b - baseDualBg.right.b
        val distR = abs(drR) + abs(drG) + abs(drB)

        return if (distL <= distR) {
            DeltaRgb(dlR, dlG, dlB)
        } else {
            DeltaRgb(drR, drG, drB)
        }
    }

    private fun captureFullWidthSkyProfile(
        pixels: IntArray,
        width: Int,
        cleanRowY: Int,
        edgeInset: Int,
        baseDualBg: DualBg
    ): IntArray {
        val profile = IntArray(width)
        val rowOffset = cleanRowY * width
        val leftFallback = packRgb(baseDualBg.left.r, baseDualBg.left.g, baseDualBg.left.b)
        val rightFallback = packRgb(baseDualBg.right.r, baseDualBg.right.g, baseDualBg.right.b)

        val scanStart = edgeInset.coerceIn(0, width - 1)
        val scanEnd = (width - 1 - edgeInset).coerceAtLeast(scanStart)
        val step = max(16, (scanEnd - scanStart) / 24)

        val anchorsX = ArrayList<Int>(28)
        val anchorsArgb = ArrayList<Int>(28)
        var ax = scanStart
        while (ax <= scanEnd) {
            val pixel = pixels[rowOffset + ax]
            val t = (ax - scanStart) / (scanEnd - scanStart).coerceAtLeast(1).toFloat()
            val expectedEdgeBg = lerpArgb(leftFallback, rightFallback, t)
            if (colorDistance(pixel, expectedEdgeBg) <= 45) {
                anchorsX.add(ax)
                anchorsArgb.add(pixel)
            }
            ax += step
        }
        if (anchorsX.isEmpty()) {
            for (x in 0 until width) {
                val t = x / (width - 1).coerceAtLeast(1).toFloat()
                profile[x] = lerpArgb(leftFallback, rightFallback, t)
            }
            return profile
        }

        val firstColor = anchorsArgb.first()
        for (x in 0..anchorsX.first()) {
            profile[x] = firstColor
        }
        for (i in 0 until anchorsX.size - 1) {
            val x0 = anchorsX[i]
            val x1 = anchorsX[i + 1]
            val p0 = anchorsArgb[i]
            val p1 = anchorsArgb[i + 1]
            val denom = (x1 - x0).coerceAtLeast(1).toFloat()
            for (x in x0..x1) {
                profile[x] = lerpArgb(p0, p1, (x - x0) / denom)
            }
        }
        val lastColor = anchorsArgb.last()
        for (x in anchorsX.last() until width) {
            profile[x] = lastColor
        }
        return profile
    }

    private fun expectedSkyPixel(skyProfile: IntArray, x: Int, delta: DeltaRgb): Int {
        val base = skyProfile[x]
        if (delta.dr == 0 && delta.dg == 0 && delta.db == 0) return base
        val r = (((base ushr 16) and 0xFF) + delta.dr).coerceIn(0, 255)
        val g = (((base ushr 8) and 0xFF) + delta.dg).coerceIn(0, 255)
        val b = ((base and 0xFF) + delta.db).coerceIn(0, 255)
        return packRgb(r, g, b)
    }

    private fun estimateBoardCenterY(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        boardX: Float,
        boardTopY: Int,
        pieceX: Float,
        pieceY: Float,
        skyProfile: IntArray,
        baseDualBg: DualBg,
        edgeInset: Int
    ): Float {
        val isoY = pieceY - ISOMETRIC_SLOPE * abs(boardX - pieceX)
        val minCenterY = boardTopY + shortEdge * 0.015f
        val maxCenterY = boardTopY + shortEdge * 0.15f

        val cx = boardX.roundToInt().coerceIn(0, width - 1)
        val probeY = (boardTopY + 3).coerceIn(0, height - 1)
        val surfaceColor = pixels[probeY * width + cx]
        val maxDepth = (shortEdge * 0.22f).toInt()
        var surfaceBottomY = probeY
        val limitY = min(height - 1, boardTopY + maxDepth)
        for (y in probeY..limitY) {
            val delta = sampleRowBgDelta(pixels, width, y, edgeInset, baseDualBg)
            val expectedBg = expectedSkyPixel(skyProfile, cx, delta)
            val p = pixels[y * width + cx]
            if (colorDistance(p, expectedBg) > 15 && colorDistance(p, surfaceColor) <= 28) {
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
        return r in 34..76 &&
            g in 34..76 &&
            b in 70..122 &&
            (b - r) >= 15 &&
            (b - g) >= 15 &&
            abs(r - g) <= 18
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

    private fun lerpArgb(p0: Int, p1: Int, t: Float): Int {
        val r0 = (p0 ushr 16) and 0xFF
        val g0 = (p0 ushr 8) and 0xFF
        val b0 = p0 and 0xFF
        val r1 = (p1 ushr 16) and 0xFF
        val g1 = (p1 ushr 8) and 0xFF
        val b1 = p1 and 0xFF
        val r = (r0 + (r1 - r0) * t).roundToInt().coerceIn(0, 255)
        val g = (g0 + (g1 - g0) * t).roundToInt().coerceIn(0, 255)
        val b = (b0 + (b1 - b0) * t).roundToInt().coerceIn(0, 255)
        return packRgb(r, g, b)
    }

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
