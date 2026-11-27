package com.jumphelper.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jumphelper.app.R
import com.jumphelper.app.core.JumpControllerState
import com.jumphelper.app.core.JumpDetector
import com.jumphelper.app.core.JumpPreferences
import com.jumphelper.app.core.ProcessDiagnostics
import com.jumphelper.app.databinding.ActivityMainBinding
import com.jumphelper.app.service.JumpAccessibilityService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: JumpPreferences
    private var suppressSeekCallback = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            JumpAccessibilityService.instance?.ensureForegroundKeepAlive()
            Toast.makeText(this, "通知权限已开启，前台防杀保活已就绪", Toast.LENGTH_SHORT).show()
        } else {
            openNotificationSettings()
        }
        updateKeepAliveBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = JumpPreferences(this)
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
        JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)

        setupEdgeToEdgeInsets()
        renderDeviceInfo()
        setupControls()
        observeControllerState()
    }

    override fun onResume() {
        super.onResume()
        // Promoting the accessibility service while Activity is visible guarantees FGS permission on Android 12–17.
        JumpAccessibilityService.instance?.ensureForegroundKeepAlive()
        updateAccessibilityStatusBanner()
        updateKeepAliveBanner()
        syncPreferencesToViews()
        binding.tvExitReason.text = ProcessDiagnostics.getLastExitSummary(this)
    }

    private fun setupEdgeToEdgeInsets() {
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                insets.left,
                insets.top + basePadding / 2,
                insets.right,
                insets.bottom + basePadding
            )
            windowInsets
        }
    }

    private fun renderDeviceInfo() {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        val shortEdge = min(w, h)
        binding.tvDeviceInfo.text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${w}×${h} · 短边 ${shortEdge}px"
    }

    private fun setupControls() {
        binding.btnAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnRestrictedSettings.setOnClickListener {
            openAppInfoForRestrictedSettings()
        }

        binding.switchForegroundKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            prefs.foregroundKeepAlive = isChecked
            JumpAccessibilityService.instance?.ensureForegroundKeepAlive()
            updateKeepAliveBanner()
        }

        binding.btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnNotificationPermission.setOnClickListener {
            requestOrOpenNotificationPermission()
        }

        binding.btnBackgroundSettings.setOnClickListener {
            openColorOsBackgroundSettings()
        }

        binding.btnLaunchWechat.setOnClickListener {
            JumpAccessibilityService.instance?.ensureForegroundKeepAlive()
            launchWeChat()
        }

        binding.btnResetOverlay.setOnClickListener {
            val service = JumpAccessibilityService.instance
            if (service != null) {
                service.resetOverlayPosition()
                binding.switchShowOverlay.isChecked = true
                Toast.makeText(this, "已重置悬浮控制条到底部零闪烁区", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.showOverlay = isChecked
            JumpAccessibilityService.instance?.refreshOverlayFromPrefs()
        }

        binding.switchAutoCalibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoCalibrateEnabled = isChecked
            JumpAccessibilityService.instance?.refreshOverlayFromPrefs()
            renderCalibrationBanner(JumpControllerState.calibrationSummary.value)
        }

        binding.switchSmartEscort.setOnCheckedChangeListener { _, isChecked ->
            prefs.smartEscortEnabled = isChecked
        }

        binding.btnResetCalibration.setOnClickListener {
            val service = JumpAccessibilityService.instance
            if (service != null) {
                service.resetAutoCalibration()
            } else {
                prefs.resetCalibration()
                JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)
                JumpControllerState.setPressOffsetMs(prefs.pressOffsetMs)
                JumpControllerState.setCalibrationSummary("自校准已重置")
            }
            syncPreferencesToViews()
            Toast.makeText(this, "已恢复推荐初始参数并重置自校准", Toast.LENGTH_SHORT).show()
        }

        binding.btnTempoTurbo.setOnClickListener {
            applyTempoPreset(JumpPreferences.TEMPO_TURBO_MS, "极速节奏已应用")
        }
        binding.btnTempoBalanced.setOnClickListener {
            applyTempoPreset(JumpPreferences.TEMPO_BALANCED_MS, "均衡推荐节奏已应用")
        }
        binding.btnTempoSteady.setOnClickListener {
            applyTempoPreset(JumpPreferences.TEMPO_STEADY_MS, "稳健节奏已应用")
        }

        binding.switchDebugMarker.setOnCheckedChangeListener { _, isChecked ->
            prefs.showDebugMarker = isChecked
        }

        binding.switchPhysicsCurve.setOnCheckedChangeListener { _, isChecked ->
            prefs.usePhysicsCurve = isChecked
        }

        binding.seekCoeff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressSeekCallback) return
                val coeff = JumpPreferences.MIN_COEFF_MS + progress
                updateCoeffValue(coeff)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnHomeMinus20.setOnClickListener {
            updateCoeffValue(prefs.jumpFullWidthMs - 20)
        }
        binding.btnHomePlus20.setOnClickListener {
            updateCoeffValue(prefs.jumpFullWidthMs + 20)
        }
        binding.btnHomeResetCoeff.setOnClickListener {
            updateCoeffValue(JumpDetector.RECOMMENDED_INITIAL_COEFF_MS)
        }

        binding.seekOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressSeekCallback) return
                val offset = JumpDetector.MIN_PRESS_OFFSET_MS + progress
                updateOffsetValue(offset)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnOffsetMinus5.setOnClickListener {
            updateOffsetValue(prefs.pressOffsetMs - 5)
        }
        binding.btnOffsetPlus5.setOnClickListener {
            updateOffsetValue(prefs.pressOffsetMs + 5)
        }
        binding.btnOffsetReset.setOnClickListener {
            updateOffsetValue(JumpPreferences.DEFAULT_PRESS_OFFSET_MS)
        }

        binding.seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressSeekCallback) return
                val minMs = JumpPreferences.MIN_INTERVAL_FLOOR_MS + progress
                prefs.minIntervalMs = minMs
                renderIntervalLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnCopyDiagnostics.setOnClickListener {
            copyDiagnosticsToClipboard()
        }
    }

    private fun applyTempoPreset(minMs: Int, toastMsg: String) {
        prefs.minIntervalMs = minMs
        suppressSeekCallback = true
        binding.seekInterval.progress = (prefs.minIntervalMs - JumpPreferences.MIN_INTERVAL_FLOOR_MS)
            .coerceIn(0, binding.seekInterval.max)
        suppressSeekCallback = false
        renderIntervalLabel()
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun syncPreferencesToViews() {
        binding.switchForegroundKeepAlive.isChecked = prefs.foregroundKeepAlive
        binding.switchShowOverlay.isChecked = prefs.showOverlay
        binding.switchAutoCalibrate.isChecked = prefs.autoCalibrateEnabled
        binding.switchSmartEscort.isChecked = prefs.smartEscortEnabled
        binding.switchDebugMarker.isChecked = prefs.showDebugMarker
        binding.switchPhysicsCurve.isChecked = prefs.usePhysicsCurve

        suppressSeekCallback = true
        val coeff = prefs.jumpFullWidthMs
        binding.seekCoeff.progress = (coeff - JumpPreferences.MIN_COEFF_MS).coerceIn(0, binding.seekCoeff.max)
        renderCoeffLabel(coeff)

        val offset = prefs.pressOffsetMs
        binding.seekOffset.progress = (offset - JumpDetector.MIN_PRESS_OFFSET_MS).coerceIn(0, binding.seekOffset.max)
        renderOffsetLabel(offset)

        val intervalProgress = (prefs.minIntervalMs - JumpPreferences.MIN_INTERVAL_FLOOR_MS)
            .coerceIn(0, binding.seekInterval.max)
        binding.seekInterval.progress = intervalProgress
        renderIntervalLabel()
        suppressSeekCallback = false

        renderCalibrationBanner(JumpControllerState.calibrationSummary.value)
    }

    private fun updateCoeffValue(rawValue: Int) {
        val clamped = rawValue.coerceIn(JumpPreferences.MIN_COEFF_MS, JumpPreferences.MAX_COEFF_MS)
        prefs.jumpFullWidthMs = clamped
        JumpControllerState.setJumpFullWidthMs(clamped)
        JumpAccessibilityService.instance?.refreshOverlayFromPrefs()

        suppressSeekCallback = true
        binding.seekCoeff.progress = clamped - JumpPreferences.MIN_COEFF_MS
        suppressSeekCallback = false
        renderCoeffLabel(clamped)
    }

    private fun updateOffsetValue(rawValue: Int) {
        val clamped = rawValue.coerceIn(JumpDetector.MIN_PRESS_OFFSET_MS, JumpDetector.MAX_PRESS_OFFSET_MS)
        prefs.pressOffsetMs = clamped
        JumpControllerState.setPressOffsetMs(clamped)
        JumpAccessibilityService.instance?.refreshOverlayFromPrefs()

        suppressSeekCallback = true
        binding.seekOffset.progress = clamped - JumpDetector.MIN_PRESS_OFFSET_MS
        suppressSeekCallback = false
        renderOffsetLabel(clamped)
    }

    private fun renderCoeffLabel(coeff: Int) {
        val ratio = coeff / JumpDetector.DEFAULT_JUMP_FULL_WIDTH_MS.toFloat()
        val ratioStr = String.format(Locale.US, "%.2fx", ratio)
        binding.tvCoeffValue.text = "满短边蓄力系数：$coeff ms ($ratioStr)"
    }

    private fun renderOffsetLabel(offset: Int) {
        val sign = if (offset > 0) "+" else ""
        binding.tvOffsetValue.text = "固定触控延迟补偿：$sign$offset ms"
    }

    private fun renderIntervalLabel() {
        binding.tvIntervalValue.text =
            "连跳节奏等待：${prefs.minIntervalMs} ~ ${prefs.maxIntervalMs} ms（长跳自动补缓冲）"
    }

    private fun renderCalibrationBanner(summary: String) {
        binding.tvCalibrationStatus.text = if (prefs.autoCalibrateEnabled) {
            "自校准状态：$summary"
        } else {
            "自校准状态：已关闭（使用手动参数）"
        }
    }

    private fun observeControllerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine<Any, String>(
                        JumpControllerState.serviceConnected,
                        JumpControllerState.jumpCount,
                        JumpControllerState.lastStatusText,
                        JumpControllerState.lastDistancePx,
                        JumpControllerState.lastPressMs,
                        JumpControllerState.lastScreenshotSize,
                        JumpControllerState.foregroundActive
                    ) { values ->
                        val connected = values[0] as Boolean
                        val count = values[1] as Int
                        val status = values[2] as String
                        val dist = values[3] as Float
                        val pressMs = values[4] as Long
                        val shotSize = values[5] as String
                        val fgActive = values[6] as Boolean

                        val connLabel = if (connected) "服务已连接" else "服务未连接"
                        val fgLabel = if (fgActive) "前台保活中" else "未保活"
                        val metric = if (pressMs > 0L) {
                            " · 最近 ${dist.toInt()}px → ${pressMs}ms"
                        } else {
                            ""
                        }
                        val shotLabel = if (shotSize.isNotEmpty()) " · 截图 $shotSize" else ""
                        "状态：$connLabel($fgLabel) · $status · 累计 $count 跳$metric$shotLabel"
                    }.collect { summary ->
                        binding.tvLiveMetrics.text = summary
                        updateAccessibilityStatusBanner()
                        updateKeepAliveBanner()
                    }
                }

                launch {
                    JumpControllerState.jumpFullWidthMs.collect { coeff ->
                        suppressSeekCallback = true
                        binding.seekCoeff.progress =
                            (coeff - JumpPreferences.MIN_COEFF_MS).coerceIn(0, binding.seekCoeff.max)
                        suppressSeekCallback = false
                        renderCoeffLabel(coeff)
                    }
                }

                launch {
                    JumpControllerState.pressOffsetMs.collect { offset ->
                        suppressSeekCallback = true
                        binding.seekOffset.progress =
                            (offset - JumpDetector.MIN_PRESS_OFFSET_MS).coerceIn(0, binding.seekOffset.max)
                        suppressSeekCallback = false
                        renderOffsetLabel(offset)
                    }
                }

                launch {
                    JumpControllerState.calibrationSummary.collect { summary ->
                        renderCalibrationBanner(summary)
                    }
                }
            }
        }
    }

    private fun updateAccessibilityStatusBanner() {
        val active = JumpControllerState.serviceConnected.value || isAccessibilitySettingEnabled()
        if (active) {
            binding.tvServiceStatus.text = "● 无障碍服务已开启 · 悬浮控制条可直接使用"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            binding.tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
            binding.btnAccessibilitySettings.text = "无障碍服务已开启（点此管理）"
        } else {
            binding.tvServiceStatus.text = "● 无障碍服务未开启（请先完成下方两步引导）"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
            binding.btnAccessibilitySettings.text = "1. 打开无障碍服务设置"
        }
    }

    private fun updateKeepAliveBanner() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(packageName)
        val notifGranted = isNotificationPermissionGranted()
        val fgActive = JumpControllerState.foregroundActive.value

        binding.btnBatteryOptimization.text = if (batteryIgnored) {
            "1. 电池已豁免 ✓"
        } else {
            "1. 忽略电池优化"
        }
        binding.btnNotificationPermission.text = if (notifGranted) {
            "2. 通知已开启 ✓"
        } else {
            "2. 开启通知权限"
        }

        val batteryLabel = if (batteryIgnored) "电池白名单✓" else "电池未豁免!"
        val notifLabel = if (notifGranted) "通知✓" else "通知未开"
        val fgLabel = if (fgActive) "前台保活✓" else "待启动保活"

        if (batteryIgnored && notifGranted && prefs.foregroundKeepAlive) {
            binding.tvKeepAliveStatus.text = "● 保活就绪：$batteryLabel · $notifLabel · $fgLabel"
            binding.tvKeepAliveStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            binding.tvKeepAliveStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
        } else {
            binding.tvKeepAliveStatus.text = "● 易被系统杀后台：$batteryLabel · $notifLabel · $fgLabel"
            binding.tvKeepAliveStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.tvKeepAliveStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        val runtimeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeOk && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已加入系统电池优化白名单", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                openColorOsBackgroundSettings()
            }
        }
    }

    private fun requestOrOpenNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            openAppInfoPage("请在应用详情页开启通知权限以便前台保活")
        }
    }

    private fun openColorOsBackgroundSettings() {
        val candidateComponents = listOf(
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        for (component in candidateComponents) {
            try {
                val intent = Intent().setComponent(component)
                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        "请开启跳一跳助手的「允许完全后台行为」与「允许自启动」",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            } catch (_: Throwable) {
            }
        }
        openAppInfoPage("请点「耗电管理」开启「允许完全后台行为」与「允许自启动」")
    }

    private fun copyDiagnosticsToClipboard() {
        val report = ProcessDiagnostics.buildDiagnosticReport(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("JumpHelper Diagnostics", report))
        Toast.makeText(this, "已复制诊断信息到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilitySettingEnabled(): Boolean {
        val expected = ComponentName(this, JumpAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expected) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "无法自动打开系统设置，请手动前往设置 -> 无障碍", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAppInfoForRestrictedSettings() {
        openAppInfoPage("请点右上角「⋮」→「允许受限设置」并验证解锁")
    }

    private fun openAppInfoPage(toastHint: String) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
            Toast.makeText(this, toastHint, Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "无法打开应用详情页，请到系统设置 -> 应用中查找", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchWeChat() {
        val launchIntent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, getString(R.string.wechat_not_installed), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
