package com.jumphelper.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import com.jumphelper.app.core.JumpDetector
import kotlin.math.min

/**
 * Low-memory screenshot pixel extractor tailored for Android 17 per-app memory limits
 * and high-resolution (1.5K / 2K, Display-P3) displays.
 *
 * Key optimizations:
 * 1. Immediately recycles hardwareBitmap and closes HardwareBuffer right after software copy.
 * 2. Downscales high-res frames (shortEdge > 840px) by 2x while normalizing to sRGB in a single
 *    Canvas pass, reducing steady working buffer from ~57MB peak down to ~3.5MB and cutting CPU by 4x.
 * 3. Reuses a single IntArray buffer across consecutive jumps and releases it on pause/trimMemory.
 */
object BitmapPixelExtractor {

    private const val HIGH_RES_SHORT_EDGE_THRESHOLD = 840

    private val bufferLock = Any()
    private var reusablePixels: IntArray? = null
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isDither = false
    }

    fun clearCache() {
        synchronized(bufferLock) {
            reusablePixels = null
        }
    }

    fun analyzeScreenshot(
        screenshotResult: AccessibilityService.ScreenshotResult,
        jumpFullWidthMs: Int,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): JumpDetector.DetectionOutcome {
        return withExtractedPixels(screenshotResult) { pixels, workWidth, workHeight, rawWidth, rawHeight ->
            val outcome = JumpDetector.detect(
                pixels = pixels,
                width = workWidth,
                height = workHeight,
                jumpFullWidthMs = jumpFullWidthMs,
                pressOffsetMs = pressOffsetMs,
                usePhysicsCurve = usePhysicsCurve
            )

            if (outcome is JumpDetector.DetectionOutcome.Success &&
                (workWidth != rawWidth || workHeight != rawHeight)
            ) {
                val sx = rawWidth / workWidth.toFloat()
                val sy = rawHeight / workHeight.toFloat()
                val rawShortEdge = min(rawWidth, rawHeight)
                val r = outcome.result
                JumpDetector.DetectionOutcome.Success(
                    r.copy(
                        pieceX = r.pieceX * sx,
                        pieceY = r.pieceY * sy,
                        boardX = r.boardX * sx,
                        boardY = r.boardY * sy,
                        boardTopY = r.boardTopY * sy,
                        distanceX = r.distanceX * sx,
                        effectiveDistance = r.effectiveDistance * sx,
                        boardWidth = r.boardWidth * sx,
                        currentBlockCenterX = r.currentBlockCenterX?.let { it * sx },
                        currentBlockWidth = r.currentBlockWidth * sx,
                        shortEdge = rawShortEdge,
                        bitmapWidth = rawWidth,
                        bitmapHeight = rawHeight
                    )
                )
            } else {
                outcome
            }
        } ?: JumpDetector.DetectionOutcome.Failure("无法解析截图缓冲区")
    }

    /**
     * Lightweight piece-only extractor for 1-Tap Smart Rescue pre-aiming.
     */
    fun detectPieceOnly(
        screenshotResult: AccessibilityService.ScreenshotResult
    ): JumpDetector.PiecePosition? {
        return withExtractedPixels(screenshotResult) { pixels, workWidth, workHeight, rawWidth, rawHeight ->
            val pos = JumpDetector.detectPieceOnly(pixels, workWidth, workHeight) ?: return@withExtractedPixels null
            if (workWidth != rawWidth || workHeight != rawHeight) {
                val sx = rawWidth / workWidth.toFloat()
                val sy = rawHeight / workHeight.toFloat()
                JumpDetector.PiecePosition(pos.x * sx, pos.y * sy, rawWidth, rawHeight)
            } else {
                pos
            }
        }
    }

    private inline fun <T> withExtractedPixels(
        screenshotResult: AccessibilityService.ScreenshotResult,
        block: (pixels: IntArray, workWidth: Int, workHeight: Int, rawWidth: Int, rawHeight: Int) -> T
    ): T? {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        var bufferClosed = false
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var workBitmap: Bitmap? = null

        return try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                ?: return null

            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return null

            // Release GPU HardwareBuffer immediately before doing CPU/canvas work
            hardwareBitmap.recycle()
            hardwareBitmap = null
            try {
                hardwareBuffer.close()
                bufferClosed = true
            } catch (_: Throwable) {
            }

            val rawWidth = softwareBitmap.width
            val rawHeight = softwareBitmap.height
            val rawShortEdge = min(rawWidth, rawHeight)
            val downscale = if (rawShortEdge > HIGH_RES_SHORT_EDGE_THRESHOLD) 2 else 1
            val workWidth = (rawWidth / downscale).coerceAtLeast(1)
            val workHeight = (rawHeight / downscale).coerceAtLeast(1)
            val needsColorConvert = softwareBitmap.colorSpace != null && !softwareBitmap.colorSpace!!.isSrgb

            val extractBitmap: Bitmap
            if (downscale > 1 || needsColorConvert) {
                val srgbSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                val dest = Bitmap.createBitmap(workWidth, workHeight, Bitmap.Config.ARGB_8888, false, srgbSpace)
                workBitmap = dest
                val srcRect = Rect(0, 0, rawWidth, rawHeight)
                val dstRect = Rect(0, 0, workWidth, workHeight)
                Canvas(dest).drawBitmap(softwareBitmap, srcRect, dstRect, scalePaint)
                softwareBitmap.recycle()
                softwareBitmap = null
                extractBitmap = dest
            } else {
                extractBitmap = softwareBitmap
            }

            synchronized(bufferLock) {
                val needed = workWidth * workHeight
                val pixels = reusablePixels?.takeIf { it.size == needed }
                    ?: IntArray(needed).also { reusablePixels = it }
                extractBitmap.getPixels(pixels, 0, workWidth, 0, 0, workWidth, workHeight)

                workBitmap?.recycle()
                workBitmap = null
                softwareBitmap?.recycle()
                softwareBitmap = null

                block(pixels, workWidth, workHeight, rawWidth, rawHeight)
            }
        } catch (_: Throwable) {
            null
        } finally {
            workBitmap?.recycle()
            softwareBitmap?.recycle()
            hardwareBitmap?.recycle()
            if (!bufferClosed) {
                try {
                    hardwareBuffer.close()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
