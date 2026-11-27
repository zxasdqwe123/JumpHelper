package com.jumphelper.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zero-extra-screenshot closed-loop online calibrator for JumpHelper V2.0.
 *
 * How it works:
 * - When Jump N is executed, we record its direction, target distance ratio, press duration,
 *   and target block width fingerprint.
 * - On Jump N+1's normal screenshot, JumpDetector measures the pawn's resting offset relative
 *   to the current standing block center (along with its width fingerprint).
 * - If both frames pass strict confidence and width-fingerprint guards, we infer whether Jump N
 *   undershot or overshot and apply a damped, clamped update to (jumpFullWidthMs, pressOffsetMs).
 */
class AutoCalibrator(
    initialSamples: Int = 0
) {
    data class PendingJump(
        val directionSign: Int,
        val targetPercent: Float,
        val pressTimeMs: Long,
        val targetConfidence: Float,
        val expectedBlockWidthRatio: Float,
        val timestampMs: Long
    )

    data class CalibrationOutcome(
        val applied: Boolean,
        val newCoeffMs: Int,
        val newOffsetMs: Int,
        val deltaCoeffMs: Int,
        val deltaOffsetMs: Int,
        val isBullseye: Boolean,
        val sampleCount: Int,
        val bullseyeStreak: Int,
        val summaryText: String
    )

    private var pendingJump: PendingJump? = null
    var sampleCount: Int = initialSamples.coerceAtLeast(0)
        private set
    var bullseyeStreak: Int = 0
        private set

    fun clearPending() {
        pendingJump = null
    }

    fun resetLearning() {
        pendingJump = null
        sampleCount = 0
        bullseyeStreak = 0
    }

    fun recordExecutedJump(
        result: JumpDetector.DetectionResult,
        executedPressMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (result.shortEdge <= 0 || result.boardWidth <= 0f) {
            pendingJump = null
            return
        }
        val dir = if (result.boardX >= result.pieceX) 1 else -1
        val targetPct = (result.effectiveDistance / result.shortEdge.toFloat()).coerceIn(0.05f, 1.3f)
        val widthRatio = result.boardWidth / result.shortEdge.toFloat()
        pendingJump = PendingJump(
            directionSign = dir,
            targetPercent = targetPct,
            pressTimeMs = executedPressMs,
            targetConfidence = result.confidence,
            expectedBlockWidthRatio = widthRatio,
            timestampMs = nowMs
        )
    }

    fun evaluateLandingAndUpdate(
        currentResult: JumpDetector.DetectionResult,
        currentCoeffMs: Int,
        currentOffsetMs: Int,
        usePhysicsCurve: Boolean,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): CalibrationOutcome {
        val prev = pendingJump
        pendingJump = null

        if (!enabled || prev == null || currentResult.shortEdge <= 0) {
            return unchanged(currentCoeffMs, currentOffsetMs, buildIdleSummary(enabled))
        }

        val elapsed = nowMs - prev.timestampMs
        if (elapsed !in MIN_ELAPSED_MS..MAX_ELAPSED_MS) {
            return unchanged(currentCoeffMs, currentOffsetMs, buildIdleSummary(true))
        }

        // Strict confidence guards: never learn from uncertain/weird blocks
        if (prev.targetConfidence < MIN_TARGET_CONFIDENCE ||
            currentResult.currentBlockCenterX == null ||
            currentResult.currentBlockConfidence < MIN_CUR_BLOCK_CONFIDENCE ||
            currentResult.currentBlockWidth <= 0f ||
            prev.expectedBlockWidthRatio <= 0.05f
        ) {
            return unchanged(currentCoeffMs, currentOffsetMs, buildIdleSummary(true))
        }

        // Width fingerprint match: blocks adjacent-block collisions and fresh game restarts
        val curWidthRatio = currentResult.currentBlockWidth / currentResult.shortEdge.toFloat()
        val widthMismatch = abs(curWidthRatio - prev.expectedBlockWidthRatio) / prev.expectedBlockWidthRatio
        if (widthMismatch > MAX_WIDTH_MISMATCH_RATIO) {
            return unchanged(currentCoeffMs, currentOffsetMs, buildIdleSummary(true))
        }

        // Positive = overshot (jumped past center along jump direction); Negative = undershot (fell short)
        val signedErrorPx = (currentResult.pieceX - currentResult.currentBlockCenterX) * prev.directionSign
        val errorRatio = signedErrorPx / currentResult.shortEdge.toFloat()

        // Outlier rejection (e.g., manual intervention or extreme landing bounce)
        if (abs(errorRatio) > MAX_OUTLIER_ERROR_RATIO) {
            bullseyeStreak = 0
            return unchanged(currentCoeffMs, currentOffsetMs, buildIdleSummary(true))
        }

        // Deadzone: within ~0.85% shortEdge (~10px on 1.5K) is bullseye — lock in without drifting
        if (abs(errorRatio) <= BULLSEYE_DEADZONE_RATIO) {
            sampleCount++
            bullseyeStreak++
            val label = if (bullseyeStreak >= 2) {
                "🎯 连中×$bullseyeStreak · 已锁准"
            } else {
                "🎯 靶心命中 · 已锁准"
            }
            return CalibrationOutcome(
                applied = false,
                newCoeffMs = currentCoeffMs,
                newOffsetMs = currentOffsetMs,
                deltaCoeffMs = 0,
                deltaOffsetMs = 0,
                isBullseye = true,
                sampleCount = sampleCount,
                bullseyeStreak = bullseyeStreak,
                summaryText = label
            )
        }

        bullseyeStreak = 0
        val targetPx = (prev.targetPercent * currentResult.shortEdge).coerceAtLeast(1f)
        val relError = (signedErrorPx / targetPx).coerceIn(-0.24f, 0.24f)
        // If undershot (relError < 0), neededDeltaMs > 0 (press longer)
        val neededDeltaMs = -relError * prev.pressTimeMs.toFloat()

        val learningRate = when {
            sampleCount < 3 -> 0.42f
            sampleCount < 7 -> 0.28f
            else -> 0.16f
        }

        val offsetShare = if (prev.targetPercent < 0.35f) 0.38f else 0.16f
        val coeffShare = 1f - offsetShare

        val rawDeltaOffset = (neededDeltaMs * learningRate * offsetShare).roundToInt()
            .coerceIn(-MAX_STEP_OFFSET_MS, MAX_STEP_OFFSET_MS)

        val baseAtTarget = JumpDetector.calculateAutoPressTimeMs(
            distanceX = prev.targetPercent * 1000f,
            shortEdge = 1000,
            jumpFullWidthMs = currentCoeffMs,
            pressOffsetMs = 0,
            usePhysicsCurve = usePhysicsCurve
        ).toFloat().coerceAtLeast(180f)

        val rawDeltaCoeff = ((neededDeltaMs * learningRate * coeffShare) * (currentCoeffMs / baseAtTarget))
            .roundToInt()
            .coerceIn(-MAX_STEP_COEFF_MS, MAX_STEP_COEFF_MS)

        val newCoeff = (currentCoeffMs + rawDeltaCoeff).coerceIn(
            JumpPreferences.MIN_COEFF_MS,
            JumpPreferences.MAX_COEFF_MS
        )
        val newOffset = (currentOffsetMs + rawDeltaOffset).coerceIn(
            JumpDetector.MIN_PRESS_OFFSET_MS,
            JumpDetector.MAX_PRESS_OFFSET_MS
        )
        val appliedCoeff = newCoeff - currentCoeffMs
        val appliedOffset = newOffset - currentOffsetMs
        sampleCount++

        val dirText = if (signedErrorPx < 0f) "偏近补强" else "偏远回调"
        val signCoeff = if (appliedCoeff >= 0) "+" else ""
        val label = if (appliedCoeff != 0) {
            "自校准: $dirText ${signCoeff}${appliedCoeff}ms"
        } else if (appliedOffset != 0) {
            val signOff = if (appliedOffset >= 0) "+" else ""
            "自校准: $dirText 延迟${signOff}${appliedOffset}ms"
        } else {
            buildIdleSummary(true)
        }

        return CalibrationOutcome(
            applied = appliedCoeff != 0 || appliedOffset != 0,
            newCoeffMs = newCoeff,
            newOffsetMs = newOffset,
            deltaCoeffMs = appliedCoeff,
            deltaOffsetMs = appliedOffset,
            isBullseye = false,
            sampleCount = sampleCount,
            bullseyeStreak = 0,
            summaryText = label
        )
    }

    private fun unchanged(coeff: Int, offset: Int, summary: String): CalibrationOutcome =
        CalibrationOutcome(
            applied = false,
            newCoeffMs = coeff,
            newOffsetMs = offset,
            deltaCoeffMs = 0,
            deltaOffsetMs = 0,
            isBullseye = false,
            sampleCount = sampleCount,
            bullseyeStreak = bullseyeStreak,
            summaryText = summary
        )

    private fun buildIdleSummary(enabled: Boolean): String {
        if (!enabled) return "自校准已关闭"
        return when {
            bullseyeStreak >= 2 -> "🎯 连中×$bullseyeStreak · 已锁准"
            sampleCount >= 4 -> "自校准已收敛 (${sampleCount}次)"
            sampleCount > 0 -> "自校准学习中 (${sampleCount}次)"
            else -> "自校准待机"
        }
    }

    companion object {
        private const val MIN_ELAPSED_MS = 850L
        private const val MAX_ELAPSED_MS = 18_000L
        private const val MIN_TARGET_CONFIDENCE = 0.78f
        private const val MIN_CUR_BLOCK_CONFIDENCE = 0.75f
        private const val MAX_WIDTH_MISMATCH_RATIO = 0.14f
        private const val MAX_OUTLIER_ERROR_RATIO = 0.125f
        private const val BULLSEYE_DEADZONE_RATIO = 0.0085f
        private const val MAX_STEP_COEFF_MS = 28
        private const val MAX_STEP_OFFSET_MS = 8
    }
}
