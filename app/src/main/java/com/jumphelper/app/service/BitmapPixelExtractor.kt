package com.jumphelper.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import com.jumphelper.app.core.JumpDetector

object BitmapPixelExtractor {

    fun analyzeScreenshot(
        screenshotResult: AccessibilityService.ScreenshotResult,
        jumpFullWidthMs: Int,
        pressOffsetMs: Int = 0,
        usePhysicsCurve: Boolean = true
    ): JumpDetector.DetectionOutcome {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var srgbBitmap: Bitmap? = null
        return try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                ?: return JumpDetector.DetectionOutcome.Failure("无法解析截图缓冲区")

            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return JumpDetector.DetectionOutcome.Failure("无法转换截图位图格式")

            val srgbSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            val width = softwareBitmap.width
            val height = softwareBitmap.height
            val targetBitmap = if (softwareBitmap.colorSpace == null || softwareBitmap.colorSpace!!.isSrgb) {
                softwareBitmap
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, srgbSpace).also { dest ->
                    srgbBitmap = dest
                    Canvas(dest).drawBitmap(softwareBitmap, 0f, 0f, null)
                }
            }

            val pixels = IntArray(width * height)
            targetBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            JumpDetector.detect(
                pixels = pixels,
                width = width,
                height = height,
                jumpFullWidthMs = jumpFullWidthMs,
                pressOffsetMs = pressOffsetMs,
                usePhysicsCurve = usePhysicsCurve
            )
        } catch (t: Throwable) {
            JumpDetector.DetectionOutcome.Failure("图像分析异常: ${t.message ?: "未知错误"}")
        } finally {
            srgbBitmap?.recycle()
            softwareBitmap?.recycle()
            hardwareBitmap?.recycle()
            try {
                hardwareBuffer.close()
            } catch (_: Throwable) {
            }
        }
    }
}
