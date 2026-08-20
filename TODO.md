# 需求记录

> 记录待实现/待办需求，按时间倒序排列。已实现的条目移到「已完成」并保留简述。

## 待实现

（暂无）

---

## 已完成

### 1. 图片旋转（90° 步进）— 完成于 2026-08-20

- 调节区一个「旋转」按钮（逆时针旋转箭头图标 `ic_rotate_ccw`），每次点击源图逆时针旋转 90°；未选图时按钮置灰，选图后从 0° 开始，连续点击累计（0°→逆90°→逆180°→逆270°→0°）。
- 实现：`MainActivity` 记录累计角度 `rotationDeg`（每次 +90 取模 360），用 `Matrix.postRotate(-deg)` 对源图生成旋转副本后重设 `CropView` 源图（裁剪框回到初始 cover 状态），预览实时刷新；0° 时直接复用原图，无额外拷贝。
- 与亮度/对比度/抖动/阈值/反色任意组合；旋转发生在 1bpp 打包之前，协议与固件不变。
- 涉及文件：`ic_rotate_ccw.xml`（图标）、`activity_main.xml`（btnRotate 控件）、`MainActivity.kt`（`rotationDeg` / `applyRotation()` / `rotateBitmap()`）。
- `assembleDebug` 编译通过。
