# JumpHelper · 微信跳一跳助手（Android 16 单机版）

专为 **无电脑、无 Root、单台 Android 手机（Android 11–16 / API 30–36）** 打造的原生 Kotlin 微信跳一跳辅助工具。

- **最新编译 APK 下载**：前往 [GitHub Releases](https://github.com/zxasdqwe123/JumpHelper/releases/latest) 直接用手机浏览器下载 `JumpHelper-debug.apk` 安装（同时每次构建也会上传到 GitHub Actions Artifacts）。

---

## 核心特性

1. **纯 Kotlin 原生像素识别（零 OpenCV 依赖）**
   - 参考开源项目 [`wangshub/wechat_jump_game`](https://github.com/wangshub/wechat_jump_game)（Apache-2.0）中 `wechat_jump_auto_slim.py` 的全面屏短边比例自适应思路。
   - 不打包任何 `.so` 原生库，APK 体积精简、响应延迟低（`< 5ms` 像素矩阵分析）。
   - **高分屏与全面屏强化（v1.1.0）**：自动将 Display-P3 / 广色域截图标准化为 sRGB；采用全宽天空背景轮廓 + 双侧边缘中位数追踪，抗径向背景渐晕、贴边方块与 ColorOS 侧边栏干扰；顶面赤道追踪抗高光偏移；棋子竖向躯干连续性校验防深色方块误识别。

2. **Android 16–17 / ColorOS 17 单机深度适配**
   - **前台常驻防杀保活（v1.2.0）**：无障碍服务启用 `specialUse` 前台常驻通知，将进程优先级提升至前台服务级；首页一键引导「忽略电池优化」「通知权限」「ColorOS 后台/自启动管理」，并内置 `ApplicationExitInfo` 进程被杀原因诊断（可识别 Android 17 `MemoryLimiter:AnonSwap` 与 ColorOS 17 冻结/省电策略）。
   - **低内存截屏管线（v1.2.0）**：高分屏（1.5K/2K）截图即时释放 `HardwareBuffer`，单次 2× 降采样同步完成 P3→sRGB 转换并复用像素缓冲，单跳内存峰值下降 70%+，同时自带截图/手势超时看门狗防止悬浮条异常隐身。
   - **无弹窗静默截图**：基于 `AccessibilityService.takeScreenshot()`（API 30+），截图瞬间自动短暂隐身悬浮条，杜绝遮挡且无需 MediaProjection 弹窗或状态栏录屏提示。
   - **拟人防检测长按**：基于 `AccessibilityService.dispatchGesture()`，在屏幕安全区域随机生成按压起点、加入微米级手指微移与随机间隔停顿（默认 `2200~2800ms`，等待落地弹跳、加分飘字与视角稳态）。
   - **无障碍悬浮控制条 (`TYPE_ACCESSIBILITY_OVERLAY`)**：无需额外申请「显示在其他应用上层」权限，支持：
     - `▶ 自动 / ⏸ 暂停`：自动连续识别跳跃
     - `⏭ 单步`：单次识别并在屏幕显示 `1.25s` 棋子/落点准星标线，便于调试系数
     - `🎯 双点`：**手动双点瞄准兜底**（依次点击「棋子底座」→「目标中心」，自动按水平投影还原 30° 等距距离起跳）
     - `-50 / -15 / 系数 / +15 / +50`：实时调校满短边蓄力系数（默认 `1700ms`，长按可重置），App 首页还支持开关抛物线修正与 `-120~+120ms` 固定触控延迟补偿
     - 支持拖动位置与一键收起为「跳」字悬浮球

3. **Android 13–17「受限设置」单机引导**
   - App 首页内置 **「1. 打开无障碍服务设置」** 与 **「2. 允许受限设置（跳转应用信息页）」** 一键直达按钮及步骤说明，解决手机浏览器侧载安装 APK 后系统拦截开启无障碍的问题。

---

## 手机端安装与启用步骤（Android 13–17 / ColorOS 17）

1. 在手机浏览器打开本仓库的 [Releases 页面](https://github.com/zxasdqwe123/JumpHelper/releases/latest)，下载并安装 `JumpHelper-debug.apk`。
2. 打开 **跳一跳助手**，点击首页 **「1. 打开无障碍服务设置」**，找到 **JumpHelper 跳一跳助手** 并尝试开启（若弹出“受限制的设置”提示，点确定即可触发系统解锁项）。
3. 返回 App 点击 **「2. 允许受限设置（跳转应用信息页）」** → 点击右上角 **「⋮」** → 选择 **「允许受限设置」** 并通过指纹或锁屏密码验证。
4. 再次点击 **「1. 打开无障碍服务设置」** 打开开关，悬浮控制条即会出现在屏幕上。
5. **ColorOS 17 / Android 17 防杀后台必做**：
   - 在 App 首页点击 **「1. 忽略电池优化」**、**「2. 开启通知权限」**，并在 **「3. ColorOS 耗电/后台自启动设置」** 中打开「允许完全后台行为」与「允许自启动」；
   - 在最近任务界面给 **跳一跳助手** 卡片「🔒 锁定」；
   - 如仍被杀，在系统「设置 → 电池 → 省电设置」中关闭 ColorOS 17 的 **「自适应电量」**。
6. 点击 **「打开微信」** 进入跳一跳游戏界面，即可使用 `▶ 自动`、`⏭ 单步` 或 `🎯 双点`！

---

## 算法原理说明

- 设屏幕短边为 `shortEdge = min(width, height)`、水平距离占比 $p = \frac{|boardX - pieceX|}{shortEdge}$：
  - **自动/单步模式（默认开启物理抛物线修正）**：按微信跳一跳引擎重力与斜抛模型（$v_y = 135 + 15t$、$v_z = 70t$、$g = 720$）精确反解蓄力秒数：
    $$t(p) = \left(-4.5 + \sqrt{20.25 + 16.3645 \cdot p}\right) \times \frac{\text{coeff}}{1700} + \Delta t_{\text{offset}}$$
    同时在首页提供开关可切换纯线性 $\text{coeff} \times p$ 模式，兼容部分高分屏（如 1.5K `1272×2800`）真机微调习惯。
  - **手动双点兜底模式**：点击起点 $(x_1, y_1)$ 与目标点 $(x_2, y_2)$，标准等轴测视角下直接使用 $|x_2 - x_1|$（纵向偏移过大时按 $\cos(30.38^\circ) \approx 0.8627$ 换算），消除方块高度与纵向点击误差。

---

## 云编译（GitHub Actions）

- 配置位于 [`.github/workflows/build.yml`](.github/workflows/build.yml)，目标 SDK `targetSdk = 36`、`compileSdk = 36`、`minSdk = 30`。
- 每次推送到 `main` 分支或手动触发 `workflow_dispatch`，自动执行单元测试（`testDebugUnitTest`）与 `assembleDebug`，并将 `JumpHelper-debug.apk` 上传至 Actions Artifact 与 `latest` Release。

## 开源致谢

- 像素识别核心思路参考 [`wangshub/wechat_jump_game`](https://github.com/wangshub/wechat_jump_game)（[Apache-2.0](LICENSE)）。
