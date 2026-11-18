package com.jumphelper.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = JumpPreferences(this)
        JumpControllerState.setJumpFullWidthMs(prefs.jumpFullWidthMs)

        setupEdgeToEdgeInsets()
        renderDeviceInfo()
        setupControls()
        observeControllerState()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatusBanner()
        syncPreferencesToViews()
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

        binding.btnLaunchWechat.setOnClickListener {
            launchWeChat()
        }

        binding.btnResetOverlay.setOnClickListener {
            val service = JumpAccessibilityService.instance
            if (service != null) {
                service.resetOverlayPosition()
                binding.switchShowOverlay.isChecked = true
                Toast.makeText(this, "已重置悬浮控制条位置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.showOverlay = isChecked
            JumpAccessibilityService.instance?.refreshOverlayFromPrefs()
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
            updateCoeffValue(JumpDetector.DEFAULT_JUMP_FULL_WIDTH_MS)
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
                binding.tvIntervalValue.text =
                    "连跳随机等待：${prefs.minIntervalMs} ~ ${prefs.maxIntervalMs} ms"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun syncPreferencesToViews() {
        binding.switchShowOverlay.isChecked = prefs.showOverlay
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
        binding.tvIntervalValue.text =
            "连跳随机等待：${prefs.minIntervalMs} ~ ${prefs.maxIntervalMs} ms"
        suppressSeekCallback = false
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

    private fun observeControllerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        JumpControllerState.serviceConnected,
                        JumpControllerState.jumpCount,
                        JumpControllerState.lastStatusText,
                        JumpControllerState.lastDistancePx,
                        JumpControllerState.lastPressMs,
                        JumpControllerState.lastScreenshotSize
                    ) { values ->
                        val connected = values[0] as Boolean
                        val count = values[1] as Int
                        val status = values[2] as String
                        val dist = values[3] as Float
                        val pressMs = values[4] as Long
                        val shotSize = values[5] as String

                        val connLabel = if (connected) "服务已连接" else "服务未连接"
                        val metric = if (pressMs > 0L) {
                            " · 最近 ${dist.toInt()}px → ${pressMs}ms"
                        } else {
                            ""
                        }
                        val shotLabel = if (shotSize.isNotEmpty()) " · 截图 $shotSize" else ""
                        "状态：$connLabel · $status · 累计 $count 跳$metric$shotLabel"
                    }.collect { summary ->
                        binding.tvLiveMetrics.text = summary
                        updateAccessibilityStatusBanner()
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
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "请点右上角「⋮」→「允许受限设置」并验证解锁",
                Toast.LENGTH_LONG
            ).show()
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
