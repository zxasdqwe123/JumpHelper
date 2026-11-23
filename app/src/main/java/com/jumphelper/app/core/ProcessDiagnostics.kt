package com.jumphelper.app.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostics for diagnosing OS kills (Android 17 MemoryLimiter,
 * ColorOS 17 Hans/Athena freezer, LMKD) and uncaught exceptions without needing a PC or ADB.
 */
object ProcessDiagnostics {

    private const val PREFS_NAME = "jump_helper_diagnostics"
    private const val KEY_EVENTS = "recent_events"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val MAX_EVENTS = 10

    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = sw.toString().lines().take(12).joinToString("\n")
                val timestamp = formatTime(System.currentTimeMillis())
                val summary = "[$timestamp] 线程 ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}\n$trace"
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, summary)
                    .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                    .commit()
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordEvent(context: Context, message: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_EVENTS, "").orEmpty()
            val lines = if (existing.isBlank()) mutableListOf() else existing.split("\n").toMutableList()
            lines.add("[${formatTime(System.currentTimeMillis())}] $message")
            while (lines.size > MAX_EVENTS) {
                lines.removeAt(0)
            }
            prefs.edit().putString(KEY_EVENTS, lines.joinToString("\n")).apply()
        } catch (_: Throwable) {
        }
    }

    fun getLastExitSummary(context: Context): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCrash = prefs.getString(KEY_LAST_CRASH, null)
        val savedCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)

        val latestExit = queryExitReasons(appContext, limit = 1).firstOrNull()
        if (latestExit == null) {
            return if (!savedCrash.isNullOrBlank()) {
                "检测到上次崩溃记录：\n${savedCrash.lines().take(2).joinToString("\n")}"
            } else {
                "暂无系统强杀或异常退出记录"
            }
        }

        val reasonText = describeExitReason(latestExit)
        val timeStr = formatTime(latestExit.timestamp)
        val pssMb = latestExit.pss / 1024L
        val rssMb = latestExit.rss / 1024L
        val rawDesc = latestExit.description?.trim().orEmpty()
        val descSuffix = if (rawDesc.isNotEmpty()) "\n系统详情：$rawDesc" else ""
        val crashHint = if (!savedCrash.isNullOrBlank() && kotlin.math.abs(latestExit.timestamp - savedCrashTime) < 15_000L) {
            "\n异常堆栈：${savedCrash.lines().firstOrNull().orEmpty()}"
        } else {
            ""
        }

        return "上次退出 [$timeStr]：$reasonText（内存 PSS ${pssMb}MB / RSS ${rssMb}MB）$descSuffix$crashHint"
    }

    fun buildDiagnosticReport(context: Context): String {
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(appContext.packageName)
        val notifEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val events = prefs.getString(KEY_EVENTS, "无").orEmpty()
        val crash = prefs.getString(KEY_LAST_CRASH, null)
        val exits = queryExitReasons(appContext, limit = 5)

        return buildString {
            appendLine("=== JumpHelper 诊断信息 ===")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("电池白名单: $batteryIgnored | 通知权限: $notifEnabled | 前台保活活跃: ${JumpControllerState.foregroundActive.value}")
            appendLine("最近截图: ${JumpControllerState.lastScreenshotSize.value.ifEmpty { "无" }} | 累计跳数: ${JumpControllerState.jumpCount.value}")
            appendLine("--- 最近进程退出记录 (ApplicationExitInfo) ---")
            if (exits.isEmpty()) {
                appendLine("无记录")
            } else {
                exits.forEach { info ->
                    appendLine(
                        "[${formatTime(info.timestamp)}] reason=${info.reason}(${describeExitReason(info)}) " +
                            "importance=${info.importance} pss=${info.pss / 1024}MB rss=${info.rss / 1024}MB " +
                            "desc=${info.description ?: "null"}"
                    )
                }
            }
            if (!crash.isNullOrBlank()) {
                appendLine("--- 最近未捕获异常 ---")
                appendLine(crash)
            }
            appendLine("--- 最近服务事件 ---")
            appendLine(events)
        }.trim()
    }

    private fun queryExitReasons(context: Context, limit: Int): List<ApplicationExitInfo> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun describeExitReason(info: ApplicationExitInfo): String {
        val desc = info.description.orEmpty()
        if (desc.contains("MemoryLimiter", ignoreCase = true)) {
            return "Android 17 单应用内存上限触发 (MemoryLimiter)"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            info.reason == ApplicationExitInfo.REASON_FREEZER
        ) {
            return "系统后台冻结强杀 (ColorOS 墓碑/自适应电量)"
        }
        return when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存清理 (LMK)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "后台耗电或资源占用策略强杀"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "手动划掉或系统一键清理后台"
            ApplicationExitInfo.REASON_USER_STOPPED -> "系统/用户强制停止"
            ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 运行时崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "底层 Native 崩溃"
            ApplicationExitInfo.REASON_ANR -> "主线程无响应 (ANR)"
            ApplicationExitInfo.REASON_SIGNALED -> "收到系统强杀信号 (SIGKILL)"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更触发进程重启"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "进程初始化失败"
            ApplicationExitInfo.REASON_EXIT_SELF -> "进程主动退出"
            else -> "系统终止 (code=${info.reason})"
        }
    }

    private fun formatTime(millis: Long): String {
        return try {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
        } catch (_: Throwable) {
            millis.toString()
        }
    }
}
