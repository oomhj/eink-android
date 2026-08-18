package com.t5epaper.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.t5epaper.app.protocol.TransferProtocol

/**
 * BLE 管理：扫描、连接、MTU 协商、串行发送位图
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        const val SERVICE_UUID = "e2a1f001-5c6e-4a5b-9c3f-2f8e1d0a0001"
        const val CHAR_WRITE = "e2a1f001-5c6e-4a5b-9c3f-2f8e1d0a0002"
        const val CHAR_NOTIFY = "e2a1f001-5c6e-4a5b-9c3f-2f8e1d0a0003"
        const val TARGET_NAME = "T5-Epaper"
        const val MTU = 247

        // 设备状态通知值（与固件一致）
        const val STATE_RECEIVING = 0x01
        const val STATE_DONE = 0x02
        const val STATE_CRC_FAIL = 0x03
        const val STATE_TIMEOUT = 0x04
    }

    // 回调接口
    interface Listener {
        fun onScanResult(device: BluetoothDevice)
        fun onConnected()
        fun onDisconnected()
        fun onDeviceState(state: Int)
        fun onProgress(sent: Int, total: Int)
        fun onSendComplete()
        fun onError(msg: String)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connected = false
    private var sending = false
    private val sendQueue = ArrayDeque<ByteArray>()
    private var sendTotalFrames = 0
    private var sentFrames = 0

    // ---------------- 扫描 ----------------
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            listener?.onError("蓝牙未开启")
            return
        }
        // 先停旧扫描
        stopScan()
        adapter.bluetoothLeScanner?.startScan(scanCallback)
        Log.d(TAG, "scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name == TARGET_NAME) {
                listener?.onScanResult(result.device)
            }
        }
    }

    // ---------------- 连接 ----------------
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        connected = false
        sending = false
    }

    fun isConnected(): Boolean = connected

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                mainHandler.post { listener?.onDisconnected() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { listener?.onError("服务发现失败") }
                return
            }
            val service = g.getService(java.util.UUID.fromString(SERVICE_UUID))
            writeChar = service?.getCharacteristic(java.util.UUID.fromString(CHAR_WRITE))
            val notifyChar = service?.getCharacteristic(java.util.UUID.fromString(CHAR_NOTIFY))
            if (writeChar == null || notifyChar == null) {
                mainHandler.post { listener?.onError("设备服务不匹配") }
                return
            }
            g.setCharacteristicNotification(notifyChar, true)
            val desc = notifyChar.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            desc?.let { g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            g.requestMtu(MTU)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
            mainHandler.post { listener?.onConnected() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value
            if (v.isNotEmpty()) {
                val state = v[0].toInt() and 0xFF
                mainHandler.post { listener?.onDeviceState(state) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                sending = false
                sendQueue.clear()
                mainHandler.post { listener?.onError("写入失败 status=$status") }
                return
            }
            sentFrames++
            mainHandler.post {
                listener?.onProgress(sentFrames, sendTotalFrames)
                sendNextFromQueue()
            }
        }
    }

    // ---------------- 发送 ----------------
    @SuppressLint("MissingPermission")
    fun sendImage(data: ByteArray) {
        if (!connected || writeChar == null) {
            listener?.onError("未连接设备")
            return
        }
        if (sending) {
            listener?.onError("正在发送中")
            return
        }
        sending = true
        sendQueue.clear()
        sentFrames = 0

        val chunks = TransferProtocol.splitImage(data)
        val totalFrames = chunks.size
        sendTotalFrames = totalFrames + 2          // START + END + 数据帧
        sendQueue.addLast(TransferProtocol.startFrame(data.size, totalFrames))
        chunks.forEachIndexed { i, chunk -> sendQueue.addLast(TransferProtocol.dataFrame(i, chunk)) }
        sendQueue.addLast(TransferProtocol.endFrame(data))

        mainHandler.post { sendNextFromQueue() }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextFromQueue() {
        if (!sending) return
        val frame = sendQueue.removeFirstOrNull()
        if (frame == null) {
            sending = false
            listener?.onSendComplete()
            return
        }
        val char = writeChar ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.setValue(frame)
                @Suppress("DEPRECATION")
                gatt!!.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            sending = false
            listener?.onError("发送异常: ${e.message}")
        }
    }
}
