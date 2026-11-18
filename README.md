# JumpHelper · 微信跳一跳助手（Android 16 单机版）

专为 **无电脑、无 Root、单台 Android 手机（Android 11–16 / API 30–36）** 打造的原生 Kotlin 微信跳一跳辅助工具。

- **最新编译 APK 下载**：前往 [GitHub Releases](https://github.com/zxasdqwe123/JumpHelper/releases/latest) 直接用手机浏览器下载 `JumpHelper-debug.apk` 安装（同时每次构建也会上传到 GitHub Actions Artifacts）。

---

## 核心特性

1. **纯 Kotlin 原生像素识别（零 OpenCV 依赖）**
   - 参考开源项目 [`wangshub/wechat_jump_game`](https://github.com/wangshub/wechat_jump_game)（Apache-2.0）中 `wechat_jump_auto_slim.py` 的全面屏短边比例自适应思路。
   - 不打包任何 `.so` 原生库，APK 体积精简、响应延迟低（`< 5ms` 像素矩阵分析）。
   - 针对圆角全面屏/挖孔屏加入边缘留白、跨行防干扰校验、中位数聚类过滤以及音符/加分图标屏蔽。

2. **Android 16（API 36）单机深度适配**
   - **无弹窗静默截图**：基于 `AccessibilityService.takeScreenshot()`（API 30+），截图瞬间自动短暂隐身悬浮条，杜绝遮挡且无需 MediaProjection 弹窗或状态栏录屏提示。
   - **拟人防检测长按**：基于 `AccessibilityService.dispatchGesture()`，在屏幕安全区域随机生成按压起点、加入微米级手指微移与随机间隔停顿。
   - **无障碍悬浮控制条 (`TYPE_ACCESSIBILITY_OVERLAY`)**：无需额外申请「显示在其他应用上层」权限，支持：
     - `▶ 自动 / ⏸ 暂停`：自动连续识别跳跃
     - `⏭ 单步`：单次识别并短暂绘制棋子/落点准星标线，便于调试系数
     - `🎯 双点`：**手动双点瞄准兜底**（依次点击「棋子底座」→「目标中心」，自动换算 30° 等距蓄力时长起跳）
     - `-20 / -5 / 系数 / +5 / +20`：实时微调满短边蓄力系数（默认 `1700ms`，长按可重置）
     - 支持拖动位置与一键收起为「跳」字悬浮球

3. **Android 13–16「受限设置」单机引导**
   - App 首页内置 **「1. 打开无障碍服务设置」** 与 **「2. 允许受限设置（跳转应用信息页）」** 一键直达按钮及步骤说明，解决手机浏览器侧载安装 APK 后系统拦截开启无障碍的问题。

---

## 手机端安装与启用步骤（Android 13–16）

1. 在手机浏览器打开本仓库的 [Releases 页面](https://github.com/zxasdqwe123/JumpHelper/releases/latest)，下载并安装 `JumpHelper-debug.apk`。
2. 打开 **跳一跳助手**，点击首页 **「1. 打开无障碍服务设置」**，找到 **JumpHelper 跳一跳助手** 并尝试开启（若弹出“受限制的设置”提示，点确定即可触发系统解锁项）。
3. 返回 App 点击 **「2. 允许受限设置（跳转应用信息页）」** → 点击右上角 **「⋮」** → 选择 **「允许受限设置」** 并通过指纹或锁屏密码验证。
4. 再次点击 **「1. 打开无障碍服务设置」** 打开开关，悬浮控制条即会出现在屏幕上。
5. 点击 **「打开微信」** 进入跳一跳游戏界面，即可使用 `▶ 自动`、`⏭ 单步` 或 `🎯 双点`！

---

## 算法原理说明

- 设屏幕短边为 `shortEdge = min(width, height)`：
  - **自动/单步模式**：扫描中部 `[(h - shortEdge)/2, (h + shortEdge)/2]` 区域定位棋子底盘中心 `pieceX` 与目标方块顶点 `boardX`，按水平距离占比计算按压时长：
    $$\text{pressTimeMs} = \text{jumpFullWidthMs} \times \frac{|boardX - pieceX|}{shortEdge}$$
  - **手动双点兜底模式**：点击起点 $(x_1, y_1)$ 与目标点 $(x_2, y_2)$，利用跳一跳标准等轴测视角余弦 $\cos(30.38^\circ) \approx 0.8627$ 将欧氏距离还原为等效水平距离，与自动模式共用同一校准系数。

---

## 云编译（GitHub Actions）

- 配置位于 [`.github/workflows/build.yml`](.github/workflows/build.yml)，目标 SDK `targetSdk = 36`、`compileSdk = 36`、`minSdk = 30`。
- 每次推送到 `main` 分支或手动触发 `workflow_dispatch`，自动执行单元测试（`testDebugUnitTest`）与 `assembleDebug`，并将 `JumpHelper-debug.apk` 上传至 Actions Artifact 与 `latest` Release。

## 开源致谢

- 像素识别核心思路参考 [`wangshub/wechat_jump_game`](https://github.com/wangshub/wechat_jump_game)（[Apache-2.0](LICENSE)）。
