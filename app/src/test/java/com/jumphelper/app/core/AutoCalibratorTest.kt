package com.jumphelper.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCalibratorTest {

    private fun mockResult(
        pieceX: Float,
        boardX: Float,
        boardWidth: Float = 220f,
        confidence: Float = 0.90f,
        curBlockCenterX: Float? = pieceX,
        curBlockWidth: Float = 220f,
        curBlockConf: Float = 0.90f,
        shortEdge: Int = 1272
    ): JumpDetector.DetectionResult {
        val dx = kotlin.math.abs(boardX - pieceX)
        return JumpDetector.DetectionResult(
            pieceX = pieceX,
            pieceY = 1560f,
            boardX = boardX,
            boardY = 1280f,
            boardTopY = 1180f,
            distanceX = dx,
            shortEdge = shortEdge,
            bitmapWidth = shortEdge,
            bitmapHeight = 2800,
            pressTimeMs = 640L,
            effectiveDistance = dx,
            confidence = confidence,
            strategy = JumpDetector.DetectionStrategy.GEOMETRY_FIT,
            boardWidth = boardWidth,
            currentBlockCenterX = curBlockCenterX,
            currentBlockWidth = curBlockWidth,
            currentBlockConfidence = curBlockConf
        )
    }

    @Test
    fun undershootIncreasesCoeffAndOffsetWithinStepClamps() {
        val calibrator = AutoCalibrator()
        // Jump 1: Jumping RIGHT (+1) onto a block of width 240px
        val jump1 = mockResult(pieceX = 360f, boardX = 840f, boardWidth = 240f, confidence = 0.92f)
        calibrator.recordExecutedJump(jump1, executedPressMs = 640L, nowMs = 10_000L)

        // Jump 2: Pawn landed 36px SHORT (left of current block center 420 -> pieceX = 384)
        val jump2 = mockResult(
            pieceX = 384f,
            boardX = 860f,
            curBlockCenterX = 420f,
            curBlockWidth = 242f, // matches 240px fingerprint (< 1%)
            curBlockConf = 0.90f
        )
        val out = calibrator.evaluateLandingAndUpdate(
            currentResult = jump2,
            currentCoeffMs = 1700,
            currentOffsetMs = 0,
            usePhysicsCurve = true,
            enabled = true,
            nowMs = 12_500L
        )
        assertTrue("Expected calibration adjustment on undershoot", out.applied)
        assertTrue("Coeff should increase on undershoot: ${out.newCoeffMs}", out.newCoeffMs > 1700)
        assertTrue("Coeff step must respect <= 28ms clamp", out.deltaCoeffMs in 1..28)
        assertTrue("Offset step must respect <= 8ms clamp", out.deltaOffsetMs in 0..8)
    }

    @Test
    fun overshootDecreasesCoeffCleanly() {
        val calibrator = AutoCalibrator()
        // Jump 1: Jumping LEFT (-1) onto block of width 210px
        val jump1 = mockResult(pieceX = 880f, boardX = 400f, boardWidth = 210f, confidence = 0.95f)
        calibrator.recordExecutedJump(jump1, executedPressMs = 680L, nowMs = 20_000L)

        // Jump 2: Jumping left (-1), landing past center means pieceX < curBlockCenterX
        val jump2 = mockResult(
            pieceX = 370f,
            boardX = 840f,
            curBlockCenterX = 415f,
            curBlockWidth = 208f,
            curBlockConf = 0.90f
        )
        val out = calibrator.evaluateLandingAndUpdate(
            currentResult = jump2,
            currentCoeffMs = 1820,
            currentOffsetMs = 10,
            usePhysicsCurve = true,
            enabled = true,
            nowMs = 22_000L
        )
        assertTrue(out.applied)
        assertTrue("Coeff should decrease on overshoot: ${out.newCoeffMs}", out.newCoeffMs < 1820)
        assertTrue("Delta must be clamped >= -28ms", out.deltaCoeffMs in -28..-1)
    }

    @Test
    fun bullseyeDeadzonePreservesParametersAndTracksStreak() {
        val calibrator = AutoCalibrator()
        val jump1 = mockResult(pieceX = 360f, boardX = 840f, boardWidth = 220f)
        calibrator.recordExecutedJump(jump1, executedPressMs = 640L, nowMs = 1_000L)

        // Only 4px offset (< 0.85% of 1272 = 10.8px) -> Bullseye deadzone
        val jump2 = mockResult(pieceX = 404f, boardX = 860f, curBlockCenterX = 400f, curBlockWidth = 221f)
        val out = calibrator.evaluateLandingAndUpdate(
            currentResult = jump2,
            currentCoeffMs = 1760,
            currentOffsetMs = 5,
            usePhysicsCurve = true,
            enabled = true,
            nowMs = 3_000L
        )
        assertFalse("Bullseye should not drift parameters", out.applied)
        assertTrue(out.isBullseye)
        assertEquals(1760, out.newCoeffMs)
        assertEquals(5, out.newOffsetMs)
        assertEquals(1, out.bullseyeStreak)
    }

    @Test
    fun rejectsAdjacentBlockCollisionViaWidthFingerprintGuard() {
        val calibrator = AutoCalibrator()
        // Previous target was a narrow green table (width = 154px)
        val jump1 = mockResult(pieceX = 300f, boardX = 820f, boardWidth = 154f)
        calibrator.recordExecutedJump(jump1, executedPressMs = 650L, nowMs = 5_000L)

        // Current standing block collided with adjacent orange box (width inflated to 215px -> +39% mismatch!)
        val jump2 = mockResult(
            pieceX = 350f,
            boardX = 850f,
            curBlockCenterX = 410f,
            curBlockWidth = 215f,
            curBlockConf = 0.90f
        )
        val out = calibrator.evaluateLandingAndUpdate(
            currentResult = jump2,
            currentCoeffMs = 1740,
            currentOffsetMs = 0,
            usePhysicsCurve = true,
            enabled = true,
            nowMs = 7_200L
        )
        assertFalse("Width fingerprint mismatch must reject corrupted sample", out.applied)
        assertEquals(1740, out.newCoeffMs)
    }

    @Test
    fun rejectsLowConfidenceWeirdBlockSamples() {
        val calibrator = AutoCalibrator()
        val weirdJump = mockResult(pieceX = 360f, boardX = 840f, boardWidth = 220f, confidence = 0.62f)
        calibrator.recordExecutedJump(weirdJump, executedPressMs = 640L, nowMs = 1_000L)

        val next = mockResult(pieceX = 360f, boardX = 840f, curBlockCenterX = 410f, curBlockWidth = 220f)
        val out = calibrator.evaluateLandingAndUpdate(
            currentResult = next,
            currentCoeffMs = 1740,
            currentOffsetMs = 0,
            usePhysicsCurve = true,
            enabled = true,
            nowMs = 3_000L
        )
        assertFalse("Low-confidence target must never update calibrator", out.applied)
    }
}
