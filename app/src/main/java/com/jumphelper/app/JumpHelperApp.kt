package com.jumphelper.app

import android.app.Application
import com.jumphelper.app.core.ProcessDiagnostics
import com.jumphelper.app.service.BitmapPixelExtractor

class JumpHelperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ProcessDiagnostics.installCrashHandler(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BitmapPixelExtractor.clearCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BitmapPixelExtractor.clearCache()
    }
}
