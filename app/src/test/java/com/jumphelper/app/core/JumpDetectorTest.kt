package com.jumphelper.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class JumpDetectorTest {

    @Test
    fun detectsPieceAndDiamondTargetOn1080x2400FullScreen() {
        val w = 1080
        val h = 2400
        val pixels = createGradientBackground(w, h)

        // Pawn on left (pieceX = 320, base bottom at y = 1380)
        drawPawn(pixels, w, h, centerX = 320, bottomY = 1380, shortEdge = w)

        // Diamond target block on right (boardX = 760, topY = 980)
        drawDiamondBlock(pixels, w, h, centerX = 760, topY = 980, halfWidth = 130, halfHeight = 76)

        // Stray music note / floating score on the pawn's side (should be ignored)
        drawRect(pixels, w, h, left = 260, top = 740, right = 340, bottom = 770, color = rgb(255, 255, 255))

        val outcome = JumpDetector.detect(pixels, w, h, jumpFullWidthMs = 1700)
        assertTrue("Expected detection success, got $outcome", outcome is JumpDetector.DetectionOutcome.Success)

        val result = (outcome as JumpDetector.DetectionOutcome.Success).result
        assertEquals(320f, result.pieceX, 6f)
        assertEquals(760f, result.boardX, 8f)
        assertEquals(1080, result.shortEdge)
        // dx = 440, 1700 * (440 / 1080) ≈ 693ms
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
        // dx = 610, 1700 * (610 / 1440) ≈ 720ms
        assertTrue("Press time ${result.pressTimeMs} out of expected range", result.pressTimeMs in 695L..745L)
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
        // 30-degree isometric jump: dx = 400, dy = -231 -> euclidean = 461.9
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
        assertTrue("Manual ($manualMs) should match Auto ($autoMs) within 10ms", abs(manualMs - autoMs) <= 10L)
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

    private fun drawRect(pixels: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(h - 1)) {
            val offset = y * w
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(w - 1)) {
                pixels[offset + x] = color
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
