# 需求记录

> 记录待实现/待办需求，按时间倒序排列。已实现的条目移到「已完成」并保留简述。

## 待实现

（暂无）

---

## 已完成

### 2. App 启动图标重设计 — 完成于 2026-08-20

- 采用方案 B：纸白底 + 黑色墨水屏负形（屏内白色近山/太阳 + 灰色远山），替换默认绿机器人图标。
- 512px 母版 `icon-designs/b.png`，PIL LANCZOS 切 5 个 mipmap 密度（48/72/96/144/192）替换 `mipmap-*/ic_launcher.png`；48px 最小尺寸下细节仍可辨。
- 4 个候选设计留档 `icon-designs/`：A 黑底白屏山峦 / B 纸白负形（选用）/ C Bayer 抖动渐变圆 / D 取景框墨滴；`sheet.png` 为对比图。
- 真机（25102RKBEC）桌面效果待确认。

### 1. 图片旋转（90° 步进）— 完成于 2026-08-20

- 调节区一个「旋转」按钮（逆时针旋转箭头图标 `ic_rotate_ccw`），每次点击源图逆时针旋转 90°；未选图时按钮置灰，选图后从 0° 开始，连续点击累计（0°→逆90°→逆180°→逆270°→0°）。
- 实现：`MainActivity` 记录累计角度 `rotationDeg`（每次 +90 取模 360），用 `Matrix.postRotate(-deg)` 对源图生成旋转副本后重设 `CropView` 源图（裁剪框回到初始 cover 状态），预览实时刷新；0° 时直接复用原图，无额外拷贝。
- 与亮度/对比度/抖动/阈值/反色任意组合；旋转发生在 1bpp 打包之前，协议与固件不变。
- 涉及文件：`ic_rotate_ccw.xml`（图标）、`activity_main.xml`（btnRotate 控件）、`MainActivity.kt`（`rotationDeg` / `applyRotation()` / `rotateBitmap()`）。
- `assembleDebug` 编译通过。

---

## 工作日志

### 2026-08-20

- 排查 Terminal 崩溃：Terminal.app 2.15 与腾讯输入法 WeType 交互时空指针崩溃（`selectedRangeWithCompletionHandler` 路径，Terminal 自身 bug，与工程无关）；确认未提交改动未丢失。
- 旋转交互调整：四态单选改为单一「旋转」按钮（逆时针箭头图标），每次逆时针 90°；图标 C 形开口改为朝左、箭头加大。commit `7c972b2`。
- App 启动图标重设计：PIL 生成 4 个候选（A/B/C/D）+ 对比图，选定 B，切 5 个 mipmap 密度替换默认图标。
  - 踩坑：此版本 Pillow 的 `paste(src, box, mask)` 按 mask 硬拷贝 src 的 RGBA（忽略 src 自身 alpha、不与 dst 合成），会把底层色整体覆盖成透明；改为 `putalpha(composite(...))` 裁剪场景 alpha + `alpha_composite` 合成解决。
- 真机联调：Xiaomi 25102RKBEC（adb 路径 `/Users/mahaojie/Documents/Android/sdk/platform-tools/adb`，PATH 中无 adb）。
