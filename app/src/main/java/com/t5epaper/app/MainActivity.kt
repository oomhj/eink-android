package com.t5epaper.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.t5epaper.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BleManager.Listener, WifiManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var wifi: WifiManager

    private var originalBitmap: Bitmap? = null
    private var currentImageData: ByteArray? = null

    /** 当前传输方式是否为 Wi-Fi（蓝牙 / Wi-Fi 二选一） */
    private val useWifi get() = binding.radioWifi.isChecked

    /** 当前方式的连接状态 */
    private val connected get() = if (useWifi) wifi.isConnected() else ble.isConnected()

    private val brightness get() = binding.seekBrightness.progress - 100
    private val contrast get() = binding.seekContrast.progress
    private val mode get() =
        if (binding.radioDither.isChecked) ImageProcessor.Mode.DITHER
        else ImageProcessor.Mode.THRESHOLD
    private val filter get() =
        if (binding.radioFilterInvert.isChecked) ImageProcessor.Filter.INVERT
        else ImageProcessor.Filter.NORMAL

    /** 当前方式需要的运行时权限：Wi-Fi 仅探测接口，无需运行时权限；蓝牙按系统版本 */
    private val requiredPermissions: Array<String> get() {
        if (useWifi) return emptyArray()
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            if (useWifi) wifi.connect(binding.etIp.text.toString()) else ble.startScan()
        } else {
            toast("需要权限才能连接设备")
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val bmp = loadSampledBitmap(uri)
        if (bmp != null) {
            originalBitmap = bmp
            binding.cropView.setSourceBitmap(bmp)
            updatePreview()
            updateSendButton()
            toast("图片已加载，可缩放/拖动选择区域")
        } else {
            toast("图片加载失败")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleManager(this)
        ble.listener = this
        wifi = WifiManager(this)
        wifi.listener = this

        // 裁剪手势结束后实时刷新预览
        binding.cropView.onCropChange = { updatePreview() }

        binding.seekBrightness.progress = 100          // 亮度 0
        binding.seekContrast.progress = 100            // 对比度 100
        binding.radioDither.isChecked = true
        binding.radioBle.isChecked = true              // 默认蓝牙方式

        binding.btnPick.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.seekBrightness.setOnSeekBarChangeListener(simpleChange { updatePreview() })
        binding.seekContrast.setOnSeekBarChangeListener(simpleChange { updatePreview() })
        binding.radioDither.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.radioThreshold.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.radioFilterNormal.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.radioFilterInvert.setOnCheckedChangeListener { _, _ -> updatePreview() }

        // 传输方式切换：断开另一种方式后统一刷新状态
        binding.radioBle.setOnCheckedChangeListener { _, checked -> if (checked) onTransportChanged() }
        binding.radioWifi.setOnCheckedChangeListener { _, checked -> if (checked) onTransportChanged() }

        binding.btnConnect.setOnClickListener {
            if (useWifi) {
                if (wifi.isConnected()) {
                    wifi.disconnect()
                } else {
                    if (!checkPermissions()) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        wifi.connect(binding.etIp.text.toString())
                    }
                }
            } else {
                if (ble.isConnected()) {
                    ble.disconnect()
                } else {
                    if (!checkPermissions()) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        ble.startScan()
                    }
                }
            }
        }
        binding.btnSend.setOnClickListener {
            val data = currentImageData
            if (data == null) {
                toast("请先选图")
                return@setOnClickListener
            }
            if (useWifi) wifi.sendImage(data) else ble.sendImage(data)
        }
    }

    /** 切换传输方式：先断开另一方式（及停掉蓝牙扫描），再刷新状态 */
    private fun onTransportChanged() {
        if (useWifi) {
            if (ble.isConnected()) ble.disconnect()
            ble.stopScan()
        } else {
            if (wifi.isConnected()) wifi.disconnect()
        }
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        updateStatus()
    }

    /** 集中刷新状态栏、IP 输入行与连接按钮文案 */
    private fun updateStatus() {
        val conn = connected
        binding.ipRow.visibility = if (useWifi) View.VISIBLE else View.GONE
        binding.tvStatus.text = when {
            useWifi && conn -> "已连接设备 ${wifi.deviceIp()}"
            useWifi -> "Wi-Fi 模式 | 未连接"
            conn -> "已连接 T5-Epaper"
            else -> "未连接"
        }
        binding.btnConnect.text = when {
            useWifi && conn -> "断开设备"
            useWifi -> "连接设备"
            conn -> "断开连接"
            else -> "扫描/连接"
        }
        binding.btnConnect.isEnabled = true
        updateSendButton()
    }

    private fun checkPermissions(): Boolean =
        requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun simpleChange(block: () -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = block()
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = block()
        }

    private fun loadSampledBitmap(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 2048) sample *= 2
            val finalOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, finalOpts) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun updatePreview() {
        val src = originalBitmap ?: return
        val crop = binding.cropView.getCropRect() ?: return
        val data = ImageProcessor.process(src, crop, brightness, contrast, mode, filter)
        currentImageData = data
        binding.imagePreview.setImageBitmap(ImageProcessor.bitmapToPreview(data, 3))
        updateSendButton()
    }

    /** 发送按钮可用条件：当前方式已连接 && 已选图 */
    private fun updateSendButton() {
        binding.btnSend.isEnabled = connected && currentImageData != null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- BleManager.Listener ----------------
    override fun onScanResult(device: BluetoothDevice) {
        toast("找到设备，连接中...")
        ble.connect(device)
    }

    override fun onConnected() {
        updateStatus()
        toast("连接成功")
    }

    override fun onDisconnected() {
        updateStatus()
        toast("设备已断开")
    }

    override fun onDeviceState(state: Int) {
        when (state) {
            BleManager.STATE_RECEIVING -> binding.tvStatus.text = "设备接收中..."
            BleManager.STATE_DONE -> {
                binding.tvStatus.text = "已连接 | 显示完成"
                binding.progressBar.progress = binding.progressBar.max
            }
            BleManager.STATE_CRC_FAIL -> toast("设备校验失败，请重试")
            BleManager.STATE_TIMEOUT -> toast("传输超时")
        }
    }

    override fun onProgress(sent: Int, total: Int) {
        binding.progressBar.max = total
        binding.progressBar.progress = sent
        binding.tvStatus.text = "发送中 $sent/$total"
    }

    override fun onSendComplete() {
        binding.tvStatus.text = "已连接 | 等待屏幕刷新..."
        toast("发送完成，屏幕刷新中")
    }

    // ---------------- WifiManager.Listener ----------------
    override fun onConnecting() {
        binding.tvStatus.text = "正在连接设备 ${wifi.deviceIp()}…"
        binding.btnConnect.isEnabled = false
    }

    override fun onWifiConnected() {
        updateStatus()
        toast("已连接设备 ${wifi.deviceIp()}")
    }

    override fun onWifiDisconnected() {
        updateStatus()
        toast("已断开，设备已休眠")
    }

    override fun onSending() {
        binding.tvStatus.text = "Wi-Fi 上传中…"
        binding.progressBar.isIndeterminate = true
    }

    override fun onWifiSuccess() {
        binding.progressBar.isIndeterminate = false
        binding.tvStatus.text = "Wi-Fi 上传完成"
        toast("上传成功，可继续发送或断开")
    }

    override fun onError(msg: String) {
        binding.progressBar.isIndeterminate = false
        updateStatus()
        toast(msg)
    }
}
