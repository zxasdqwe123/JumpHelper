package com.jumphelper.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.jumphelper.app.core.JumpDetector

object BitmapPixelExtractor {

    fun analyzeScreenshot(
        screenshotResult: AccessibilityService.ScreenshotResult,
        jumpFullWidthMs: Int
    ): JumpDetector.DetectionOutcome {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        return try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                ?: return JumpDetector.DetectionOutcome.Failure("无法解析截图缓冲区")

            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return JumpDetector.DetectionOutcome.Failure("无法转换截图位图格式")

            val width = softwareBitmap.width
            val height = softwareBitmap.height
            val pixels = IntArray(width * height)
            softwareBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            JumpDetector.detect(
                pixels = pixels,
                width = width,
                height = height,
                jumpFullWidthMs = jumpFullWidthMs
            )
        } catch (t: Throwable) {
            JumpDetector.DetectionOutcome.Failure("图像分析异常: ${t.message ?: "未知错误"}")
        } finally {
            softwareBitmap?.recycle()
            hardwareBitmap?.recycle()
            try {
                hardwareBuffer.close()
            } catch (_: Throwable) {
            }
        }
    }
}
