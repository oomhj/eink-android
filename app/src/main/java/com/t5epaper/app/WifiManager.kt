package com.t5epaper.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wi-Fi 设备管理：**连接判定完全基于设备健康接口（GET /ping 返回 200 "pong"），不依赖 Wi-Fi 名**。
 *
 * 对应固件 src/album_main.cpp（env esp8266-album），接口见 docs/album_upload_api.md。
 * 连接时**不主动发起 Wi-Fi 连接**——需要手机已连到设备所在网络：
 *   - 相册 AP 模式：手机手动连上 AP "album"（无密码，网关 192.168.4.1），IP 填 192.168.4.1；
 *   - 局域网直连：手机和设备在同一 LAN，IP 填设备的局域网地址（如 192.168.1.50）。
 * 点「连接设备」直接用当前活动网络探测 /ping。
 *
 * 上传：POST /img（4000 字节 1bpp）；结束：POST /done（上传成功自动调用，让设备深睡）。
 */
class WifiManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiManager"
        const val AP_IP = "192.168.4.1"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val PROBE_TIMEOUT_MS = 3_000
        private val IP_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    }

    /** 回调接口（方法名与 BleManager 区分，避免混淆） */
    interface Listener {
        fun onConnecting()          // 正在探测设备
        fun onWifiConnected()
        fun onWifiDisconnected()
        fun onSending()
        fun onWifiSuccess()
        fun onError(msg: String)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var connecting = false
    private var connected = false
    private var targetIp = AP_IP
    private var network: Network? = null                        // 探测/上传路由用（当前活动网络）

    fun isConnected(): Boolean = connected
    fun deviceIp(): String = targetIp

    // ---------------- 连接 ----------------
    /** 连接设备：直接测试健康接口（GET /ping），不发起任何 Wi-Fi 连接 */
    fun connect(ip: String) {
        if (connected || connecting) return
        val t = ip.trim()
        if (!isValidIp(t)) {
            listener?.onError("设备 IP 格式不对，应为如 192.168.4.1")
            return
        }
        targetIp = t
        try {
            if (!wifiManager.isWifiEnabled) {
                listener?.onError("请先开启 Wi-Fi（需已连到设备所在网络）")
                return
            }
        } catch (e: SecurityException) {
            // 读不了 Wi-Fi 状态就继续探测，让探测结果说话
            Log.w(TAG, "isWifiEnabled: ${e.message}")
        }
        // 直接用当前活动网络测试接口
        network = connectivityManager.activeNetwork
        connecting = true
        listener?.onConnecting()
        probeDevice()
    }

    private fun isValidIp(ip: String): Boolean {
        val m = IP_REGEX.matchEntire(ip) ?: return false
        return m.groupValues.drop(1).all { it.toIntOrNull()?.let { o -> o in 0..255 } == true }
    }

    // ---------------- 探测 ----------------
    /** 同步探测一次设备健康接口（后台线程调用）：GET /ping 返回 200 "pong" 才算在线 */
    private fun probeNow(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$targetIp/ping")
            val net = network ?: connectivityManager.activeNetwork
            conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code == 200 && body.trim() == "pong"
        } catch (e: Exception) {
            Log.e(TAG, "ping failed", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 后台探测一次，结果回主线程 */
    private fun probeDevice() {
        Thread {
            val ok = probeNow()
            finishProbe(ok)
        }.start()
    }

    /** 统一收尾：探测成功 = 已连接，失败 = 报错 */
    private fun finishProbe(ok: Boolean) {
        mainHandler.post {
            if (!connecting) return@post          // 期间已断开/取消，丢弃结果
            connecting = false
            if (ok) {
                connected = true
                listener?.onWifiConnected()
            } else {
                connected = false
                listener?.onError("设备无响应，请确认 IP 正确、设备已进入上传模式且手机与设备在同一网络")
            }
        }
    }

    // ---------------- 断开 ----------------
    /**
     * 断开设备：复位本地状态，并把 /done 作为 Wi-Fi 断开指令（尽力让设备深睡）。
     * 设备在传图模式无自动超时，断开时发 /done 以免它一直待机耗电。
     */
    fun disconnect() {
        val wasConnected = connected
        connecting = false
        connected = false
        network = null
        if (wasConnected) {
            postDone()
            listener?.onWifiDisconnected()
        }
    }

    /** 尽力通知设备深睡（POST /done，空 body），不等待响应 */
    private fun postDone() {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$targetIp/done")
                val net = connectivityManager.activeNetwork
                conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = CONNECT_TIMEOUT_MS
                conn.responseCode
                Log.i(TAG, "done sent")
            } catch (e: Exception) {
                Log.w(TAG, "done failed（设备可能已离线）", e)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    // ---------------- 上传 ----------------
    fun sendImage(data: ByteArray) {
        if (!connected) {
            listener?.onError("未连接设备")
            return
        }
        listener?.onSending()
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$targetIp/img")
                val net = network ?: connectivityManager.activeNetwork
                conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(data.size)
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = CONNECT_TIMEOUT_MS
                conn.outputStream.use { it.write(data) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                mainHandler.post {
                    when (code) {
                        200 -> listener?.onWifiSuccess()   // 上传成功（设备保持唤醒，可继续传）
                        400 -> listener?.onError("设备拒绝：bad length（字节数不符）")
                        500 -> listener?.onError("设备写入失败：$body")
                        else -> listener?.onError("HTTP $code：$body")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload failed", e)
                mainHandler.post { listener?.onError("上传失败：${e.message}") }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
