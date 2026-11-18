package com.jumphelper.app.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure Kotlin pixel recognition engine for WeChat Jump (跳一跳).
 *
 * Inspired by the short-edge adaptive algorithm in wangshub/wechat_jump_game
 * (`wechat_jump_auto_slim.py`, Apache-2.0) and upgraded with:
 * - Full-width sky-profile background estimation + dual-edge vertical tracking
 * - Multi-row vertical persistence & contiguous-cluster filtering
 * - Top-face equator tracking for accurate horizontal & vertical centering on round/highlighted blocks
 * - Pawn vertical body continuity check to reject dark-purple decoy blocks
 * - Parabolic flight-gravity timing curve matching the game's physics model
 */
object JumpDetector {

    const val DEFAULT_JUMP_FULL_WIDTH_MS = 1700
    const val MIN_PRESS_MS = 200L
    const val MAX_PRESS_MS = 2800L
    const val MIN_PRESS_OFFSET_MS = -120
    const val MAX_PRESS_OFFSET_MS = 120

    // Isometric board slope is ~30.38 deg, cos(30.38 deg) ≈ 0.8627
    private const val ISOMETRIC_COS = 0.8627f
    private const val ISOMETRIC_SLOPE = 25.5f / 43.5f

    // Exact WeChat Jump parabolic parameters: 2.9167 * t^2 + 26.25 * t = 47.7297 * (dx / shortEdge)
    private const val PHYSICS_HALF_LINEAR_S = 4.5
    private const val PHYSICS_LINEAR_TERM_SQ = 20.25
    private const val PHYSICS_DISTANCE_COEFF = 16.3645

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
        jumpFullWidthMs: Int = DEFAULT_JUMP_FULL_WIDTH_MS,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): DetectionOutcome {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return DetectionOutcome.Failure("截图尺寸异常 (${width}x$height)")
        }

        val shortEdge = min(width, height)
        val scanTop = ((height - shortEdge) / 2 - shortEdge / 30)
            .coerceAtLeast((height * 0.24f).toInt())
        val scanBottom = ((height + shortEdge) / 2).coerceAtMost((height * 0.86f).toInt())
        if (scanBottom <= scanTop + 24) {
            return DetectionOutcome.Failure("有效扫描区域过小")
        }

        val piece = findPiece(pixels, width, height, shortEdge, scanTop, scanBottom)
            ?: return DetectionOutcome.Failure("未检测到棋子（请确认处于跳一跳游戏界面）")

        val board = findBoard(pixels, width, height, shortEdge, scanTop, scanBottom, piece.x, piece.y)
            ?: return DetectionOutcome.Failure("未检测到目标方块（可使用双点手动瞄准）")

        val distanceX = abs(board.x - piece.x)
        val pressTimeMs = calculateAutoPressTimeMs(
            distanceX = distanceX,
            shortEdge = shortEdge,
            jumpFullWidthMs = jumpFullWidthMs,
            pressOffsetMs = pressOffsetMs,
            usePhysicsCurve = usePhysicsCurve
        )

        return DetectionOutcome.Success(
            DetectionResult(
                pieceX = piece.x,
                pieceY = piece.y,
                boardX = board.x,
                boardY = board.y,
                boardTopY = board.topY,
                distanceX = distanceX,
                shortEdge = shortEdge,
                bitmapWidth = width,
                bitmapHeight = height,
                pressTimeMs = pressTimeMs
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

        // Isometric jumps lie near 30 deg: horizontal |dx| is invariant to block height & vertical tap error.
        val equivalentDx = if (dx >= shortEdge * 0.04f && dy <= dx * 1.15f) {
            dx
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

    private data class Point(val x: Float, val y: Float)
    private data class BoardPoint(val x: Float, val y: Float, val topY: Float)
    private data class Rgb(val r: Int, val g: Int, val b: Int)
    private data class DualBg(val left: Rgb, val right: Rgb)
    private data class DeltaRgb(val dr: Int, val dg: Int, val db: Int)
    private data class Span(val left: Int, val right: Int) {
        val width: Int get() = right - left + 1
        val midX: Float get() = (left + right) / 2f
    }

    private fun findPiece(
        pixels: IntArray,
        width: Int,
        height: Int,
        shortEdge: Int,
        scanTop: Int,
        scanBottom: Int
    ): Point? {
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
    ): Point? {
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
                    }
                }

                val candidateX = (weightedXSum / totalWeight).toFloat()
                if (hasPawnBodyAbove(pixels, width, scanTop, candidateX.roundToInt(), fy, pieceHeight, pieceWidth)) {
                    val pieceY = (fy - shortEdge * 0.018f).coerceAtLeast(scanTop.toFloat())
                    return Point(candidateX, pieceY)
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
            val checkY = (baseY - (pieceHeight * frac).toInt())
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
        pieceX: Float,
        pieceY: Float
    ): BoardPoint? {
        val pieceExclusionRadius = max(14, shortEdge / 13)
        val edgeInset = max(12, width / 40)
        val fineStepY = max(2, shortEdge / 300)
        val minBoardHits = max(5, shortEdge / 120)
        val maxScanY = min(scanBottom, (pieceY - shortEdge * 0.015f).toInt())
            .coerceAtLeast(scanTop + fineStepY)

        // Precompute full-width sky profile once right above scanTop
        val cleanProfileRowY = (scanTop - fineStepY * 2).coerceAtLeast(0)
        val baseDualBg = sampleDualOuterBg(pixels, width, cleanProfileRowY, edgeInset)
        val skyProfile = captureFullWidthSkyProfile(pixels, width, cleanProfileRowY, edgeInset, baseDualBg)

        // Primary half-screen scan avoids floating music notes / +score above current block
        val primaryRange = if (pieceX < width / 2f) {
            (width / 2) until (width - edgeInset)
        } else {
            edgeInset until (width / 2)
        }

        val primaryResult = scanBoardInRange(
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
            pieceX = pieceX,
            pieceY = pieceY,
            pieceExclusionRadius = pieceExclusionRadius,
            skyProfile = skyProfile,
            baseDualBg = baseDualBg
        )
        if (primaryResult != null) return primaryResult

        // Fallback if camera didn't recenter target across screen midpoint
        val fallbackRange = if (pieceX < width / 2f) {
            (pieceX.toInt() + pieceExclusionRadius).coerceAtMost(width / 2) until (width / 2)
        } else {
            (width / 2) until (pieceX.toInt() - pieceExclusionRadius).coerceAtLeast(width / 2)
        }
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
                pieceX = pieceX,
                pieceY = pieceY,
                pieceExclusionRadius = pieceExclusionRadius,
                skyProfile = skyProfile,
                baseDualBg = baseDualBg
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
        pieceX: Float,
        pieceY: Float,
        pieceExclusionRadius: Int,
        skyProfile: IntArray,
        baseDualBg: DualBg
    ): BoardPoint? {
        if (xRange.first >= xRange.last) return null
        val diffThreshold = 15
        val minBlockWidth = max(20, shortEdge / 30)
        val clusterGap = max(4, shortEdge / 240)

        var by = scanTop
        while (by < maxScanY) {
            val rowDelta = sampleRowBgDelta(pixels, width, by, edgeInset, baseDualBg)
            val rowOffset = by * width
            val verifyY1 = min(height - 1, by + fineStepY)
            val verifyY2 = min(height - 1, by + fineStepY * 2)
            val v1Delta = sampleRowBgDelta(pixels, width, verifyY1, edgeInset, baseDualBg)
            val v2Delta = sampleRowBgDelta(pixels, width, verifyY2, edgeInset, baseDualBg)
            val v1Offset = verifyY1 * width
            val v2Offset = verifyY2 * width

            val clusters = ArrayList<Span>(4)
            var curLeft = -1
            var lastHit = -1
            var curHits = 0

            for (bx in xRange) {
                if (abs(bx - pieceX) < pieceExclusionRadius) continue
                val bgPixel = expectedSkyPixel(skyProfile, bx, rowDelta)
                if (colorDistance(pixels[rowOffset + bx], bgPixel) > diffThreshold) {
                    val v1Bg = expectedSkyPixel(skyProfile, bx, v1Delta)
                    val v2Bg = expectedSkyPixel(skyProfile, bx, v2Delta)
                    if (colorDistance(pixels[v1Offset + bx], v1Bg) > diffThreshold &&
                        colorDistance(pixels[v2Offset + bx], v2Bg) > diffThreshold
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
                    pieceX = pieceX,
                    pieceY = pieceY
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
        pieceX: Float,
        pieceY: Float
    ): BoardPoint? {
        val trackStep = max(2, shortEdge / 320)
        val maxDepth = (shortEdge * 0.145f).toInt().coerceAtLeast(36)
        val limitY = min(height - 1, boardTopY + maxDepth)
        val gapTolerance = max(4, shortEdge / 250)
        val maxJumpGrowth = max(28, (shortEdge * 0.14f).toInt())

        // Allow top face contour to expand cleanly across screen midpoint without clipping on pawn side,
        // while staying safely clear of the pawn silhouette itself.
        val pawnSafeGap = max(14, (shortEdge * 0.045f).toInt())
        val minBoundX = if (seedMidX >= pieceX) {
            max(edgeInset, (pieceX + pawnSafeGap).toInt())
        } else {
            edgeInset
        }
        val maxBoundX = if (seedMidX < pieceX) {
            min(width - 1 - edgeInset, (pieceX - pawnSafeGap).toInt())
        } else {
            width - 1 - edgeInset
        }
        if (maxBoundX <= minBoundX + 8) return null

        val nearPawnHorizRadius = shortEdge * 0.06f
        val pawnTopBoundY = pieceY - shortEdge * 0.22f
        val trackedRows = ArrayList<Pair<Int, Span>>(32)
        var maxSpanW = 0
        var trackCenter = seedMidX.roundToInt().coerceIn(minBoundX, maxBoundX)
        var flatCount = 0

        var y = boardTopY
        while (y <= limitY) {
            val rowDelta = sampleRowBgDelta(pixels, width, y, edgeInset, baseDualBg)
            val blockPawnPixels = y >= pawnTopBoundY
            val span = measureConnectedForegroundSpan(
                pixels = pixels,
                width = width,
                y = y,
                centerSeedX = trackCenter,
                minX = minBoundX,
                maxX = maxBoundX,
                skyProfile = skyProfile,
                delta = rowDelta,
                diffThreshold = diffThreshold,
                gapTolerance = gapTolerance,
                pieceX = pieceX,
                nearPawnHorizRadius = if (blockPawnPixels) nearPawnHorizRadius else -1f
            ) ?: break

            if (trackedRows.isNotEmpty() && span.width - maxSpanW > maxJumpGrowth) {
                break
            }
            if (trackedRows.isNotEmpty() && span.width < maxSpanW * 0.45f) {
                break
            }

            trackedRows.add(y to span)
            if (span.width > maxSpanW + 3) {
                maxSpanW = span.width
                flatCount = 0
            } else if (y - boardTopY > shortEdge * 0.024f) {
                flatCount++
                if (flatCount >= 4) {
                    break
                }
            }
            trackCenter = span.midX.roundToInt().coerceIn(minBoundX, maxBoundX)
            y += trackStep
        }

        if (trackedRows.size < 3 || maxSpanW < minBlockWidth) {
            return null
        }

        var equatorIndex = trackedRows.lastIndex
        val equatorThreshold = (maxSpanW * 0.96f).toInt()
        for (i in trackedRows.indices) {
            if (trackedRows[i].second.width >= equatorThreshold) {
                equatorIndex = i
                break
            }
        }
        val equatorY = trackedRows[equatorIndex].first.toFloat()

        val sampleLimit = min(trackedRows.lastIndex, equatorIndex + 1)
        val minSampleWidth = (maxSpanW * 0.40f).toInt().coerceAtLeast(max(5, shortEdge / 120))
        val candidateMidXs = ArrayList<Float>(sampleLimit + 1)
        for (i in 0..sampleLimit) {
            val span = trackedRows[i].second
            if (span.width >= minSampleWidth && abs(span.midX - seedMidX) <= shortEdge * 0.12f) {
                candidateMidXs.add(span.midX)
            }
        }

        val boardX = if (candidateMidXs.isNotEmpty()) {
            candidateMidXs.sort()
            candidateMidXs[candidateMidXs.size / 2]
        } else {
            seedMidX
        }

        val isoY = pieceY - ISOMETRIC_SLOPE * abs(boardX - pieceX)
        val minCenterY = boardTopY + shortEdge * 0.014f
        val maxCenterY = boardTopY + shortEdge * 0.135f
        val boardY = if (equatorY in minCenterY..maxCenterY && abs(equatorY - isoY) <= shortEdge * 0.075f) {
            equatorY
        } else {
            estimateBoardCenterY(
                pixels = pixels,
                width = width,
                height = height,
                shortEdge = shortEdge,
                boardX = boardX,
                boardTopY = boardTopY,
                pieceX = pieceX,
                pieceY = pieceY,
                skyProfile = skyProfile,
                baseDualBg = baseDualBg,
                edgeInset = edgeInset
            )
        }
        return BoardPoint(boardX, boardY, boardTopY.toFloat())
    }

    private fun measureConnectedForegroundSpan(
        pixels: IntArray,
        width: Int,
        y: Int,
        centerSeedX: Int,
        minX: Int,
        maxX: Int,
        skyProfile: IntArray,
        delta: DeltaRgb,
        diffThreshold: Int,
        gapTolerance: Int,
        pieceX: Float,
        nearPawnHorizRadius: Float
    ): Span? {
        val rowOffset = y * width
        fun isBlockedPawnPixel(pxX: Int, argb: Int): Boolean {
            return nearPawnHorizRadius > 0f &&
                abs(pxX - pieceX) <= nearPawnHorizRadius &&
                isPieceColor(argb)
        }

        var anchorX = -1
        for (deltaX in 0..gapTolerance * 2) {
            val rx = (centerSeedX + deltaX).coerceAtMost(maxX)
            val rp = pixels[rowOffset + rx]
            if (!isBlockedPawnPixel(rx, rp) &&
                colorDistance(rp, expectedSkyPixel(skyProfile, rx, delta)) > diffThreshold
            ) {
                anchorX = rx
                break
            }
            val lx = (centerSeedX - deltaX).coerceAtLeast(minX)
            val lp = pixels[rowOffset + lx]
            if (!isBlockedPawnPixel(lx, lp) &&
                colorDistance(lp, expectedSkyPixel(skyProfile, lx, delta)) > diffThreshold
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
            if (colorDistance(p, expectedSkyPixel(skyProfile, x, delta)) > diffThreshold) {
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
            if (colorDistance(p, expectedSkyPixel(skyProfile, x, delta)) > diffThreshold) {
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

        // Choose whichever side remained consistent vertically (not occluded by an edge-touching block)
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
        val maxCenterY = boardTopY + shortEdge * 0.14f

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
        return r in 34..74 &&
            g in 34..74 &&
            b in 72..120 &&
            (b - r) >= 16 &&
            (b - g) >= 16 &&
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
