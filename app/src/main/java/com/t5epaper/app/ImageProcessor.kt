package com.t5epaper.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图像预处理：把任意图片转换成 122x250 1bit 墨水屏位图
 * 流水线: 缩放居中裁剪 -> 灰度 -> 亮度/对比度 -> 抖动或阈值 -> 1bpp
 */
object ImageProcessor {
    const val SCREEN_W = 122
    const val SCREEN_H = 250

    enum class Mode { DITHER, THRESHOLD }
    enum class Filter { NORMAL, INVERT }

    /**
     * 主处理入口
     * @param src 原始图片
     * @param crop 源图像素坐标的裁剪区域（122:250 比例，来自 CropView）
     * @param brightness -100..100
     * @param contrast   0..200 (100=不变)
     * @param mode       抖动 / 阈值
     * @param filter     滤镜（原图 / 反色）
     * @return 1bpp 位图数据, 每行16字节, 1=黑, 共 4000 字节
     */
    fun process(
        src: Bitmap,
        crop: RectF,
        brightness: Int,
        contrast: Int,
        mode: Mode,
        filter: Filter
    ): ByteArray {
        // 1. 取裁剪区域并缩放到屏幕尺寸
        val gray = cropToGray(src, crop)

        // 2. 亮度/对比度 映射表（LUT 加速）
        val lut = IntArray(256)
        val b = brightness / 100.0 * 64          // -64..64
        val c = contrast / 100.0                 // 0..2
        for (i in 0..255) {
            var v = (i - 128.0) * c + 128.0 + b
            v = if (v < 0) 0.0 else if (v > 255) 255.0 else v
            lut[i] = v.roundToInt()
        }

        val grayArr = IntArray(SCREEN_W * SCREEN_H)
        var idx = 0
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                grayArr[idx] = lut[gray[idx]]
                idx++
            }
        }

        // 3. 二值化
        val bits = if (mode == Mode.DITHER) {
            floydSteinberg(grayArr)
        } else {
            threshold(grayArr)
        }

        // 4. 滤镜：反色 = 黑白对调（在原图基础上翻转每一位）
        if (filter == Filter.INVERT) {
            for (i in bits.indices) bits[i] = !bits[i]
        }

        // 5. 打包成 1bpp MSB-first, 每行 16 字节
        return packBits(bits)
    }

    /** 取源图像素坐标区域并缩放到屏幕尺寸 + 灰度化（一次 Canvas 绘制，支持小数坐标 + 双线性） */
    private fun cropToGray(src: Bitmap, crop: RectF): IntArray {
        val bw = src.width.toFloat()
        val bh = src.height.toFloat()
        val r = RectF(
            crop.left.coerceIn(0f, bw), crop.top.coerceIn(0f, bh),
            crop.right.coerceIn(0f, bw), crop.bottom.coerceIn(0f, bh)
        )
        if (r.width() < 1f) r.right = min(r.left + 1f, bw)
        if (r.height() < 1f) r.bottom = min(r.top + 1f, bh)

        // 用矩阵把源区域映射到整屏（保留小数坐标，双线性）
        val m = Matrix()
        val sx = SCREEN_W / r.width()
        val sy = SCREEN_H / r.height()
        m.postScale(sx, sy)
        m.postTranslate(-r.left * sx, -r.top * sy)

        val bmp = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)   // 双线性，与原 createScaledBitmap(...,true) 一致
        canvas.drawBitmap(src, m, paint)

        val pixels = IntArray(SCREEN_W * SCREEN_H)
        bmp.getPixels(pixels, 0, SCREEN_W, 0, 0, SCREEN_W, SCREEN_H)
        bmp.recycle()

        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            // 亮度感知加权灰度
            gray[i] = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)
        }
        return gray
    }

    /** 阈值二值化: >127 白(0), <=127 黑(1) */
    private fun threshold(gray: IntArray): BooleanArray {
        val bits = BooleanArray(gray.size)
        for (i in gray.indices) bits[i] = gray[i] <= 127
        return bits
    }

    /** Floyd-Steinberg 抖动 */
    private fun floydSteinberg(gray: IntArray): BooleanArray {
        val n = gray.size
        val buf = IntArray(n)
        System.arraycopy(gray, 0, buf, 0, n)
        val bits = BooleanArray(n)

        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val i = y * SCREEN_W + x
                val old = buf[i]
                val black = old <= 127
                bits[i] = black
                val err = old - if (black) 0 else 255

                // 右
                if (x + 1 < SCREEN_W) buf[i + 1] = clampByte(buf[i + 1] + err * 7 / 16)
                // 左下
                if (x > 0 && y + 1 < SCREEN_H) buf[i + SCREEN_W - 1] = clampByte(buf[i + SCREEN_W - 1] + err * 3 / 16)
                // 下
                if (y + 1 < SCREEN_H) buf[i + SCREEN_W] = clampByte(buf[i + SCREEN_W] + err * 5 / 16)
                // 右下
                if (x + 1 < SCREEN_W && y + 1 < SCREEN_H) buf[i + SCREEN_W + 1] = clampByte(buf[i + SCREEN_W + 1] + err * 1 / 16)
            }
        }
        return bits
    }

    private fun clampByte(v: Int) = min(255, max(0, v))

    /** 打包 1bpp: MSB-first, 每行 16 字节 */
    private fun packBits(bits: BooleanArray): ByteArray {
        val rowBytes = (SCREEN_W + 7) / 8          // 16
        val out = ByteArray(SCREEN_H * rowBytes)
        var i = 0
        for (y in 0 until SCREEN_H) {
            for (b in 0 until rowBytes) {
                var byte = 0
                for (k in 0 until 8) {
                    val x = b * 8 + k
                    if (x < SCREEN_W && bits[y * SCREEN_W + x]) {
                        byte = byte or (0x80 shr k)
                    }
                }
                out[i++] = byte.toByte()
            }
        }
        return out
    }

    /** 从 1bpp 位图生成预览 Bitmap（放大 scale 倍，黑白两色） */
    fun bitmapToPreview(data: ByteArray, scale: Int): Bitmap {
        val rowBytes = (SCREEN_W + 7) / 8
        val bmp = Bitmap.createBitmap(SCREEN_W * scale, SCREEN_H * scale, Bitmap.Config.ARGB_8888)
        val px = IntArray(SCREEN_W * SCREEN_H)
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val byte = data[y * rowBytes + x / 8]
                val bit = (byte.toInt() ushr (7 - (x % 8))) and 1
                px[y * SCREEN_W + x] = if (bit == 1) Color.BLACK else Color.WHITE
            }
        }
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val c = px[y * SCREEN_W + x]
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        bmp.setPixel(x * scale + dx, y * scale + dy, c)
                    }
                }
            }
        }
        return bmp
    }
}
