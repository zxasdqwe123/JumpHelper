package com.jumphelper.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

class JumpDetectorTest {

    @Test
    fun detectsPieceAndDiamondTargetOn1080x2400FullScreen() {
        val w = 1080
        val h = 2400
        val pixels = createGradientBackground(w, h)

        // Pawn on left (pieceX = 320, base bottom at y = 1380)
        drawPawn(pixels, w, h, centerX = 320, bottomY = 1380, shortEdge = w)

        // Diamond target block on right at true 30-deg isometric elevation (boardX = 760, boardY = 1106)
        drawDiamondBlock(pixels, w, h, centerX = 760, topY = 1030, halfWidth = 130, halfHeight = 76)

        // Stray music note / floating score on the pawn's side (should be ignored)
        drawRect(pixels, w, h, left = 260, top = 740, right = 340, bottom = 770, color = rgb(255, 255, 255))

        val outcome = JumpDetector.detect(pixels, w, h, jumpFullWidthMs = 1700)
        assertTrue("Expected detection success, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)

        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(320f, result.pieceX, 6f)
        assertEquals(760f, result.boardX, 8f)
        assertEquals(1080, result.shortEdge)
        // dx = 440 on 30-deg axis, parabolic formula yields ~688ms
        assertTrue("Press time ${result.pressTimeMs} out of expected range", result.pressTimeMs in 670L..715L)
    }

    @Test
    fun detectsPieceOnRightAndCircleTargetOn1440x3200Screen() {
        val w = 1440
        val h = 3200
        val pixels = createGradientBackground(w, h)

        // Pawn on right (pieceX = 1020, bottomY = 1820)
        drawPawn(pixels, w, h, centerX = 1020, bottomY = 1820, shortEdge = w)

        // Target block on left (boardX = 410, topY = 1280)
        drawDiamondBlock(pixels, w, h, centerX = 410, topY = 1280, halfWidth = 160, halfHeight = 92)

        val outcome = JumpDetector.detect(pixels, w, h, jumpFullWidthMs = 1700)
        assertTrue("Expected detection success, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)

        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(1020f, result.pieceX, 8f)
        assertEquals(410f, result.boardX, 10f)
        assertEquals(1440, result.shortEdge)
        // dx = 610, parabolic formula yields ~714ms
        assertTrue("Press time ${result.pressTimeMs} out of expected range", result.pressTimeMs in 695L..745L)
    }

    @Test
    fun ignoresRadialVignetteAndEdgeSidebarOn1272x2800OnePlus15K() {
        val w = 1272
        val h = 2800
        val pixels = createRadialVignetteBackground(w, h)

        // ColorOS Smart Sidebar / edge pill on both left and right borders (x < 20)
        drawRect(pixels, w, h, left = 0, top = 740, right = 18, bottom = 1120, color = rgb(245, 248, 255))
        drawRect(pixels, w, h, left = w - 18, top = 760, right = w - 1, bottom = 1140, color = rgb(245, 248, 255))

        // Pawn on left (pieceX = 360, bottomY = 1580)
        drawPawn(pixels, w, h, centerX = 360, bottomY = 1580, shortEdge = w)

        // Target block slightly tall at topY = 1040, boardX = 890
        drawDiamondBlock(pixels, w, h, centerX = 890, topY = 1040, halfWidth = 140, halfHeight = 82)

        val outcome = JumpDetector.detect(pixels, w, h, jumpFullWidthMs = 1700)
        assertTrue("Expected detection success under radial vignette, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)

        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(360f, result.pieceX, 6f)
        assertEquals(890f, result.boardX, 8f)
        assertEquals(1272, result.shortEdge)
        assertEquals(1272, result.bitmapWidth)
        assertEquals(2800, result.bitmapHeight)
    }

    @Test
    fun rejectsThinRippleAndFloatingPopupAboveTargetBlock() {
        val w = 1272
        val h = 2800
        val pixels = createGradientBackground(w, h)

        drawPawn(pixels, w, h, centerX = 380, bottomY = 1600, shortEdge = w)

        // Thin 3px horizontal ripple / wave above target side (should be ignored)
        drawRect(pixels, w, h, left = 720, top = 840, right = 860, bottom = 842, color = rgb(255, 255, 255))
        // Tiny floating +2 score glyph (narrow 18px x 22px, should be ignored)
        drawRect(pixels, w, h, left = 760, top = 890, right = 778, bottom = 912, color = rgb(252, 252, 252))

        // Actual target block below
        drawEllipseBlock(pixels, w, h, centerX = 910, topY = 1080, rx = 125, ry = 72, color = rgb(142, 198, 176))

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue("Expected real block detection after ignoring ripple/popup, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)

        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(910f, result.boardX, 8f)
        assertEquals(1080f, result.boardTopY, 8f)
    }

    @Test
    fun centersAccuratelyOnCylinderWithOffCenterSpecularGlint() {
        val w = 1272
        val h = 2800
        val pixels = createGradientBackground(w, h)

        drawPawn(pixels, w, h, centerX = 920, bottomY = 1580, shortEdge = w)
        drawEllipseBlock(pixels, w, h, centerX = 390, topY = 1090, rx = 120, ry = 68, color = rgb(165, 178, 196))
        // Asymmetric highlight near upper-left rim (top-left lighting) that shifts topmost row
        drawRect(pixels, w, h, left = 328, top = 1088, right = 368, bottom = 1096, color = rgb(250, 250, 252))

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue(outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(390f, result.boardX, 8f)
    }

    @Test
    fun rejectsDarkPurpleDecoyBlockWithoutPawnBodyAbove() {
        val w = 1272
        val h = 2800
        val pixels = createGradientBackground(w, h)

        // Flat dark-purple decoy strip near scanBottom (no vertical pawn body above it)
        drawRect(pixels, w, h, left = 520, top = 1920, right = 600, bottom = 1945, color = rgb(52, 52, 92))

        // Real pawn above at centerX = 350, bottomY = 1560
        drawPawn(pixels, w, h, centerX = 350, bottomY = 1560, shortEdge = w)
        drawDiamondBlock(pixels, w, h, centerX = 880, topY = 1060, halfWidth = 135, halfHeight = 78)

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue(outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(350f, result.pieceX, 6f)
        assertEquals(880f, result.boardX, 8f)
    }

    @Test
    fun parabolicPhysicsReducesLongJumpOvershootComparedToLinear() {
        val shortEdge = 1272
        val coeff = 1700
        val longLinear = JumpDetector.calculateAutoPressTimeMs(
            distanceX = shortEdge * 0.60f,
            shortEdge = shortEdge,
            jumpFullWidthMs = coeff,
            usePhysicsCurve = false
        )
        val longParabolic = JumpDetector.calculateAutoPressTimeMs(
            distanceX = shortEdge * 0.60f,
            shortEdge = shortEdge,
            jumpFullWidthMs = coeff,
            usePhysicsCurve = true
        )
        assertTrue(
            "Long jump parabolic ($longParabolic ms) should trim overshoot below linear ($longLinear ms)",
            longParabolic in 965L..1000L && longParabolic < longLinear - 20L
        )

        val offsetApplied = JumpDetector.calculateAutoPressTimeMs(
            distanceX = shortEdge * 0.60f,
            shortEdge = shortEdge,
            jumpFullWidthMs = coeff,
            pressOffsetMs = 25,
            usePhysicsCurve = true
        )
        assertEquals(longParabolic + 25L, offsetApplied)
    }

    @Test
    fun returnsFailureWhenNoPawnPresent() {
        val w = 720
        val h = 1600
        val pixels = createGradientBackground(w, h)
        drawDiamondBlock(pixels, w, h, centerX = 480, topY = 620, halfWidth = 80, halfHeight = 46)

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue(outcome is JumpDetector.DetectionOutcome.Failure)
    }

    @Test
    fun manualTwoPointCalculationMatchesAutoIsometricScale() {
        val w = 1080
        val h = 2400
        val autoMs = JumpDetector.calculateAutoPressTimeMs(distanceX = 400f, shortEdge = w, jumpFullWidthMs = 1700)
        val manualMs = JumpDetector.calculateManualPressTimeMs(
            x1 = 300f,
            y1 = 1300f,
            x2 = 700f,
            y2 = 1069f,
            screenWidth = w,
            screenHeight = h,
            jumpFullWidthMs = 1700
        )
        assertEquals(autoMs, manualMs)
    }

    @Test
    fun downscaled636x1400MatchesFull1272x2800PressTimeAndCenters() {
        val fullW = 1272
        val fullH = 2800
        val fullPixels = createRadialVignetteBackground(fullW, fullH)
        drawPawn(fullPixels, fullW, fullH, centerX = 368, bottomY = 1580, shortEdge = fullW)
        drawDiamondBlock(fullPixels, fullW, fullH, centerX = 892, topY = 1044, halfWidth = 140, halfHeight = 82)

        val fullOutcome = JumpDetector.detect(fullPixels, fullW, fullH, jumpFullWidthMs = 1700)
        assertTrue(fullOutcome is JumpDetector.DetectionOutcome.Success)
        val fullRes = (fullOutcome as JumpDetector.DetectionOutcome.Success).result

        val halfW = fullW / 2
        val halfH = fullH / 2
        val halfPixels = IntArray(halfW * halfH)
        for (y in 0 until halfH) {
            val srcRow = (y * 2) * fullW
            val dstRow = y * halfW
            for (x in 0 until halfW) {
                halfPixels[dstRow + x] = fullPixels[srcRow + x * 2]
            }
        }

        val halfOutcome = JumpDetector.detect(halfPixels, halfW, halfH, jumpFullWidthMs = 1700)
        assertTrue("Downscaled 636x1400 detection failed: $halfOutcome", halfOutcome is JumpDetector.DetectionOutcome.Success)
        val halfRes = (halfOutcome as JumpDetector.DetectionOutcome.Success).result

        assertEquals(fullRes.pieceX, halfRes.pieceX * 2f, 6f)
        assertEquals(fullRes.boardX, halfRes.boardX * 2f, 8f)
        assertTrue(
            "Press time mismatch: full=${fullRes.pressTimeMs}ms vs half=${halfRes.pressTimeMs}ms",
            abs(fullRes.pressTimeMs - halfRes.pressTimeMs) <= 10L
        )
    }

    @Test
    fun stripsCastGroundShadowOnGreenRoundTableUnderPinkBackground() {
        val w = 636
        val h = 1400
        val pixels = createTintedBackground(w, h, baseR = 226, baseG = 210, baseB = 212)

        drawPawn(pixels, w, h, centerX = 180, bottomY = 820, shortEdge = w)

        // Heavy 0.70x multiplicative cast ground shadow extending far to the lower-left of the green table
        drawGroundShadowEllipse(pixels, w, h, centerX = 438, centerY = 636, rx = 86, ry = 54, factor = 0.70f)
        // Actual green round table centered at (498, 616)
        drawEllipseBlock(pixels, w, h, centerX = 498, topY = 570, rx = 78, ry = 46, color = rgb(99, 147, 105))

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue("Expected detection success on shadowed green table, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals("Shadow should not drag boardX left", 498f, result.boardX, 4.5f)
        assertEquals("Thin ellipse equator should hit true cy", 616f, result.boardY, 6f)
        assertTrue("Expected high confidence, got ${result.confidence}", result.confidence >= 0.80f)
    }

    @Test
    fun centersAccuratelyOnAsymmetricLongRectangularBench() {
        val w = 636
        val h = 1400
        val pixels = createTintedBackground(w, h, baseR = 204, baseG = 207, baseB = 218)

        drawPawn(pixels, w, h, centerX = 185, bottomY = 815, shortEdge = w)

        // Asymmetric parallelogram top face: top vertex at (415, 550), short left edge L1=40, long right edge L2=110.
        // Left corner: (415 - 35, 550 + 20) = (380, 570)
        // Right corner: (415 + 95, 550 + 55) = (510, 605)
        // True geometric center = ((380+510)/2, (570+605)/2) = (445, 587.5) — 30px away from topX!
        drawAsymmetricParallelogram(
            pixels, w, h,
            topX = 415f, topY = 550f,
            leftSpanX = 35f, leftSpanY = 20f,
            rightSpanX = 95f, rightSpanY = 55f,
            color = rgb(168, 124, 88)
        )

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue("Expected asymmetric bench detection, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals("Asymmetric bench centerX should use (left+right)/2, not topX", 445f, result.boardX, 6f)
        assertEquals("Asymmetric bench centerY should use (leftY+rightY)/2", 587.5f, result.boardY, 8f)
    }

    @Test
    fun detectsMultiColorRubiksCubeWithoutFragmenting() {
        val w = 636
        val h = 1400
        val pixels = createTintedBackground(w, h, baseR = 204, baseG = 207, baseB = 218)

        drawPawn(pixels, w, h, centerX = 450, bottomY = 810, shortEdge = w)

        // 3x3 colorful Rubik's cube diamond top face centered at (200, 630)
        val cubeColors = intArrayOf(
            rgb(230, 58, 52), rgb(246, 212, 48), rgb(52, 132, 232),
            rgb(58, 186, 86), rgb(245, 130, 40), rgb(248, 248, 248)
        )
        drawMultiColorDiamondBlock(pixels, w, h, centerX = 200, topY = 576, halfWidth = 96, halfHeight = 54, palette = cubeColors)

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue("Expected Rubik's cube detection, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(200f, result.boardX, 6f)
        assertEquals(630f, result.boardY, 8f)
    }

    @Test
    fun ignoresNarrowTopCapProtrusionAndCentersOnMainDeck() {
        val w = 636
        val h = 1400
        val pixels = createTintedBackground(w, h, baseR = 204, baseG = 207, baseB = 218)

        drawPawn(pixels, w, h, centerX = 190, bottomY = 810, shortEdge = w)

        // Main wide landing deck centered at (445, 645)
        drawDiamondBlock(pixels, w, h, centerX = 445, topY = 595, halfWidth = 92, halfHeight = 52)
        // Narrow off-center signboard/bottle-cap protrusion sitting right on top of the deck
        drawRect(pixels, w, h, left = 422, top = 566, right = 446, bottom = 598, color = rgb(235, 96, 72))

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue("Expected detection past top protrusion, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(445f, result.boardX, 8f)
    }

    @Test
    fun locksSubPixelWhiteBullseyeDotOnColoredBlockAndRejectsOnWhiteBlock() {
        val w = 636
        val h = 1400
        val pixels = createTintedBackground(w, h, baseR = 204, baseG = 207, baseB = 218)

        drawPawn(pixels, w, h, centerX = 430, bottomY = 800, shortEdge = w)
        drawEllipseBlock(pixels, w, h, centerX = 215, topY = 588, rx = 80, ry = 46, color = rgb(104, 152, 108))
        // Exact WeChat Jump #F4F4F4 target dot (22x12 ellipse on 636px shortEdge) centered at (217, 634)
        drawEllipseBlock(pixels, w, h, centerX = 217, topY = 628, rx = 11, ry = 6, color = rgb(244, 244, 244))

        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue(outcome is JumpDetector.DetectionOutcome.Success)
        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(JumpDetector.DetectionStrategy.WHITE_DOT, result.strategy)
        assertEquals(217f, result.boardX, 1.5f)
        assertEquals(634f, result.boardY, 1.5f)
        assertTrue("White dot lock should carry >= 0.95 confidence", result.confidence >= 0.95f)

        // Now verify a pure white block does NOT falsely trigger WHITE_DOT
        val whitePixels = createTintedBackground(w, h, baseR = 204, baseG = 207, baseB = 218)
        drawPawn(whitePixels, w, h, centerX = 430, bottomY = 800, shortEdge = w)
        drawEllipseBlock(whitePixels, w, h, centerX = 215, topY = 588, rx = 80, ry = 46, color = rgb(246, 246, 246))
        val whiteOutcome = JumpDetector.detect(whitePixels, w, h) as JumpDetector.DetectionOutcome.Success
        assertEquals(JumpDetector.DetectionStrategy.GEOMETRY_FIT, whiteOutcome.result.strategy)
    }

    @Test
    fun detectsDarkGameOverOverlayGracefully() {
        val w = 636
        val h = 1400
        val pixels = IntArray(w * h) { rgb(48, 52, 60) }
        val outcome = JumpDetector.detect(pixels, w, h)
        assertTrue(outcome is JumpDetector.DetectionOutcome.Failure)
        assertTrue("Expected isGameOverScreen=true", (outcome as JumpDetector.DetectionOutcome.Failure).isGameOverScreen)
    }

    @Test
    fun isometric3DBackProjectionMatchesOnAxisAndCorrectsOffAxis() {
        // Strictly on 30 deg axis: dy = dx * tan(30) => d3d == dx
        val onAxis = JumpDetector.computeEffectiveDistance(
            dx = 400f,
            dy = 400f * 0.57735f,
            shortEdge = 1272,
            strategy = JumpDetector.DetectionStrategy.WHITE_DOT,
            confidence = 0.98f
        )
        assertEquals(400f, onAxis, 1.0f)

        // Pawn landed slightly forward/lower than block center => dy is larger than dx * tan(30),
        // so true 3D jump distance must be longer than dx alone!
        val offAxisLonger = JumpDetector.computeEffectiveDistance(
            dx = 400f,
            dy = 265f,
            shortEdge = 1272,
            strategy = JumpDetector.DetectionStrategy.WHITE_DOT,
            confidence = 0.98f
        )
        assertTrue("Expected 3D back-projection ($offAxisLonger) > dx (400)", offAxisLonger > 418f)
    }

    private fun createGradientBackground(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val r = (224 - t * 22).toInt()
            val g = (232 - t * 18).toInt()
            val b = (218 - t * 14).toInt()
            val color = rgb(r, g, b)
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = color
            }
        }
        return pixels
    }

    private fun createRadialVignetteBackground(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        val cx = w / 2f
        val cy = h * 0.45f
        val maxR = hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
        for (y in 0 until h) {
            val dy = y - cy
            val offset = y * w
            for (x in 0 until w) {
                val dx = x - cx
                val distRatio = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / maxR).coerceIn(0f, 1f)
                // Notice center sky is brighter than edges by ~24 RGB levels (total Manhattan > 70 across row!)
                val r = (226 - distRatio * 24).toInt()
                val g = (232 - distRatio * 22).toInt()
                val b = (218 - distRatio * 20).toInt()
                pixels[offset + x] = rgb(r, g, b)
            }
        }
        return pixels
    }

    private fun drawPawn(pixels: IntArray, w: Int, h: Int, centerX: Int, bottomY: Int, shortEdge: Int) {
        val pawnColor = rgb(54, 54, 93)
        val halfBaseW = (shortEdge * 0.033f).toInt().coerceAtLeast(10)
        val bodyH = (shortEdge * 0.14f).toInt().coerceAtLeast(40)
        val topY = (bottomY - bodyH).coerceAtLeast(0)
        for (y in topY..bottomY.coerceAtMost(h - 1)) {
            val progress = (y - topY).toFloat() / bodyH.coerceAtLeast(1)
            val halfW = (halfBaseW * (0.45f + 0.55f * progress)).toInt().coerceAtLeast(4)
            val rowOffset = y * w
            for (x in (centerX - halfW).coerceAtLeast(0)..(centerX + halfW).coerceAtMost(w - 1)) {
                pixels[rowOffset + x] = pawnColor
            }
        }
    }

    private fun drawDiamondBlock(
        pixels: IntArray,
        w: Int,
        h: Int,
        centerX: Int,
        topY: Int,
        halfWidth: Int,
        halfHeight: Int
    ) {
        val blockTopColor = rgb(245, 245, 248)
        val endY = (topY + halfHeight * 2).coerceAtMost(h - 1)
        for (y in topY.coerceAtLeast(0)..endY) {
            val dy = abs(y - (topY + halfHeight))
            val ratio = 1f - (dy.toFloat() / halfHeight.coerceAtLeast(1))
            val hw = (halfWidth * ratio).toInt().coerceAtLeast(1)
            val rowOffset = y * w
            for (x in (centerX - hw).coerceAtLeast(0)..(centerX + hw).coerceAtMost(w - 1)) {
                pixels[rowOffset + x] = blockTopColor
            }
        }
    }

    private fun drawEllipseBlock(
        pixels: IntArray,
        w: Int,
        h: Int,
        centerX: Int,
        topY: Int,
        rx: Int,
        ry: Int,
        color: Int
    ) {
        val cy = topY + ry
        val endY = (topY + ry * 2).coerceAtMost(h - 1)
        for (y in topY.coerceAtLeast(0)..endY) {
            val normY = (y - cy).toFloat() / ry.coerceAtLeast(1)
            val underSqrt = (1f - normY * normY).coerceAtLeast(0f)
            val hw = (rx * sqrt(underSqrt)).toInt().coerceAtLeast(1)
            val rowOffset = y * w
            for (x in (centerX - hw).coerceAtLeast(0)..(centerX + hw).coerceAtMost(w - 1)) {
                pixels[rowOffset + x] = color
            }
        }
    }

    private fun drawRect(pixels: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(h - 1)) {
            val offset = y * w
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(w - 1)) {
                pixels[offset + x] = color
            }
        }
    }

    private fun createTintedBackground(w: Int, h: Int, baseR: Int, baseG: Int, baseB: Int): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val r = (baseR - t * 14).toInt().coerceIn(0, 255)
            val g = (baseG - t * 14).toInt().coerceIn(0, 255)
            val b = (baseB - t * 12).toInt().coerceIn(0, 255)
            val c = rgb(r, g, b)
            val off = y * w
            for (x in 0 until w) pixels[off + x] = c
        }
        return pixels
    }

    private fun drawGroundShadowEllipse(
        pixels: IntArray,
        w: Int,
        h: Int,
        centerX: Int,
        centerY: Int,
        rx: Int,
        ry: Int,
        factor: Float
    ) {
        for (y in (centerY - ry).coerceAtLeast(0)..(centerY + ry).coerceAtMost(h - 1)) {
            val ny = (y - centerY) / ry.toFloat().coerceAtLeast(1f)
            val hw = (rx * sqrt((1f - ny * ny).coerceAtLeast(0f))).toInt()
            val off = y * w
            for (x in (centerX - hw).coerceAtLeast(0)..(centerX + hw).coerceAtMost(w - 1)) {
                val p = pixels[off + x]
                val r = (((p ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val g = (((p ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * factor).toInt().coerceIn(0, 255)
                pixels[off + x] = rgb(r, g, b)
            }
        }
    }

    private fun drawAsymmetricParallelogram(
        pixels: IntArray,
        w: Int,
        h: Int,
        topX: Float,
        topY: Float,
        leftSpanX: Float,
        leftSpanY: Float,
        rightSpanX: Float,
        rightSpanY: Float,
        color: Int
    ) {
        // Fill parallelogram parameterized by u, v in [0, 1]: P(u,v) = Top + u*(-leftSpanX, leftSpanY) + v*(rightSpanX, rightSpanY)
        val steps = 220
        for (iu in 0..steps) {
            val u = iu / steps.toFloat()
            for (iv in 0..steps) {
                val v = iv / steps.toFloat()
                val px = (topX - u * leftSpanX + v * rightSpanX).toInt()
                val py = (topY + u * leftSpanY + v * rightSpanY).toInt()
                if (px in 0 until w && py in 0 until h) {
                    pixels[py * w + px] = color
                }
            }
        }
    }

    private fun drawMultiColorDiamondBlock(
        pixels: IntArray,
        w: Int,
        h: Int,
        centerX: Int,
        topY: Int,
        halfWidth: Int,
        halfHeight: Int,
        palette: IntArray
    ) {
        val endY = (topY + halfHeight * 2).coerceAtMost(h - 1)
        for (y in topY.coerceAtLeast(0)..endY) {
            val dy = abs(y - (topY + halfHeight))
            val ratio = 1f - (dy.toFloat() / halfHeight.coerceAtLeast(1))
            val hw = (halfWidth * ratio).toInt().coerceAtLeast(1)
            val rowOffset = y * w
            val cellY = ((y - topY) * 3) / (halfHeight * 2).coerceAtLeast(1)
            for (x in (centerX - hw).coerceAtLeast(0)..(centerX + hw).coerceAtMost(w - 1)) {
                val cellX = ((x - (centerX - halfWidth)) * 3) / (halfWidth * 2).coerceAtLeast(1)
                val color = palette[(cellX + cellY * 3).coerceAtLeast(0) % palette.size]
                pixels[rowOffset + x] = color
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
