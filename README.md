# T5Epaper-Android

TTGO T5 墨水屏传图的安卓端（Kotlin + viewBinding，无 Compose）。

从 `TTGO_T5_V2.3_2.13` 仓库中抽取出来的独立工程，固件（PlatformIO）在 `../TTGO_T5_V2.3_2.13`。

## 功能

- 从相册选图（Photo Picker），裁剪 / 亮度 / 对比度 / 抖动（Floyd–Steinberg）或阈值二值化
- 在本机预处理成 122×250 1bpp 位图（4000 字节，1 = 黑，MSB-first，每行 16 字节）
- 传输通道二选一：
  - **BLE**：连接设备 `T5-Epaper`（服务 `e2a1f001-5c6e-4a5b-9c3f-2f8e1d0a0001`）
  - **Wi-Fi**：手机先连上相册设备热点（`album`，开放），对设备 IP `POST /img`；`GET /ping` 判活，`POST /done` 断开休眠

## 传输协议

帧格式与固件 `src/bt_image_server.cpp` 及 `tools/ble_send.py` 保持一致，
实现见 `app/src/main/java/com/t5epaper/app/protocol/TransferProtocol.kt`：

```
MAGIC(2)=0xAA 0x55 | TYPE(1) | SEQ(1) | LEN(2) | PAYLOAD | CRC16(2)
  0x01 START — totalBytes(2) + totalFrames(2)
  0x02 DATA  — 位图分片，MAX_PAYLOAD=236
  0x03 END   — 整图 CRC32(4)
```

**改协议时三处同步**：固件 `bt_image_server.cpp`、本工程的 `TransferProtocol.kt`、`tools/ble_send.py`。

## 构建

```sh
./gradlew assembleDebug   # Android Studio 直接打开也行
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。
minSdk 24 / compileSdk 34 / Java & Kotlin 17 / Gradle 8.14.3。
无单元测试。

## 主要代码

| 文件 | 职责 |
|---|---|
| `app/.../MainActivity.kt` | 选图、参数调节、连接/发送入口 |
| `app/.../BleManager.kt` | BLE 扫描/连接、MTU 247、通知订阅、逐帧串行发送 |
| `app/.../WifiManager.kt` | Wi-Fi 通道：`/ping` 判活、`POST /img` 上传、`POST /done` 断开 |
| `app/.../ImageProcessor.kt` | 等比缩放 + 中心裁剪 → 灰度 → 亮度/对比度 LUT → 抖动/阈值 → 反转 → 打包 1bpp |
| `app/.../protocol/TransferProtocol.kt` | 帧构造（与固件镜像） |
