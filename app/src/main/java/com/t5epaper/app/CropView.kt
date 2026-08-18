package com.t5epaper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪视图：在固定裁剪框（122:250，与墨水屏同比例）内选择图片区域。
 * 单指拖动平移、双指捏合缩放、双击复位；裁剪框外变暗。
 * 初始态（cover 缩放 + 居中）与旧居中裁剪数学等价。
 */
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var src: Bitmap? = null
    private var viewW = 0
    private var viewH = 0
    private val winRect = RectF()        // 裁剪框（视图坐标）
    private var coverScale = 1f          // 初始 cover 缩放（即最小缩放）
    private var scale = 1f               // 当前缩放
    private var offsetX = 0f             // 位图左上角（视图坐标）
    private var offsetY = 0f
    private val matrix = Matrix()        // 源像素 -> 视图坐标
    private val inverse = Matrix()
    private var needsNotify = false      // 本次手势中矩阵是否变化过
    private var scaling = false          // 是否处于捏合手势

    /** 手势结束回调：Activity 用它重跑预览 */
    var onCropChange: (() -> Unit)? = null

    // 复用对象，避免 onDraw / onTouch 每次分配
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE
    }
    private val gridPaint = Paint().apply {
        strokeWidth = 1f; color = Color.argb(120, 255, 255, 255)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER
    }
    private val tmpRect = RectF()

    init {
        // 不需要焦点高亮/滚动指示等系统装饰
        isFocusable = true
    }

    // ---------------- 图片设置 ----------------
    fun setSourceBitmap(bmp: Bitmap) {
        src = bmp
        if (viewW > 0 && viewH > 0) resetToInitial()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        computeWindow()
        if (src != null) resetToInitial()
    }

    /** 裁剪框：122:250 比例，高度方向留 20% 边距，居中 */
    private fun computeWindow() {
        if (viewW <= 0 || viewH <= 0) return
        val ratio = ImageProcessor.SCREEN_H.toFloat() / ImageProcessor.SCREEN_W   // ≈2.049
        val winH = min(viewH * 0.8f, viewW * 0.8f * ratio)
        val winW = winH / ratio
        val l = (viewW - winW) / 2f
        val t = (viewH - winH) / 2f
        winRect.set(l, t, l + winW, t + winH)
    }

    /** 复位：cover 缩放（位图恰好覆盖裁剪框）+ 居中 */
    private fun resetToInitial() {
        val s = src ?: return
        val sw = s.width.toFloat()
        val sh = s.height.toFloat()
        coverScale = max(winRect.width() / sw, winRect.height() / sh)
        scale = coverScale
        offsetX = winRect.centerX() - sw * scale / 2f
        offsetY = winRect.centerY() - sh * scale / 2f
        applyTransform()
        needsNotify = false
    }

    /** 源像素 -> 视图坐标；先缩放后平移 */
    private fun applyTransform() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
    }

    // ---------------- 手势 ----------------
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaling = false
                fireChangeIfNeeded()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true   // 必须 true 才能收到后续事件

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  distanceX: Float, distanceY: Float): Boolean {
                // 捏合期间（scaling 或两指）不做平移，避免乱跳
                if (scaling || e2.pointerCount >= 2) return false
                panBy(-distanceX, -distanceY)   // 内容跟手；真机如方向反则改符号
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetToInitial()
                fireChange()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 阻止外层 ScrollView 拦截垂直拖动
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!scaling) fireChangeIfNeeded()
        }
        return true
    }

    /** 围绕焦点 F 缩放：保持焦点下的源像素不动 */
    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newScale = (scale * factor).coerceIn(coverScale, coverScale * MAX_ZOOM)
        if (newScale == scale) return
        offsetX = fx - (fx - offsetX) * (newScale / scale)
        offsetY = fy - (fy - offsetY) * (newScale / scale)
        scale = newScale
        clampOffsets()      // 先改 scale 再夹取
        applyTransform()
        invalidate()
        needsNotify = true
    }

    private fun panBy(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        clampOffsets()
        applyTransform()
        invalidate()
        needsNotify = true
    }

    /** 位图必须完全覆盖裁剪框（scale >= coverScale 保证夹取区间合法） */
    private fun clampOffsets() {
        val s = src ?: return
        val drawnW = s.width * scale
        val drawnH = s.height * scale
        offsetX = offsetX.coerceIn(winRect.right - drawnW, winRect.left)
        offsetY = offsetY.coerceIn(winRect.bottom - drawnH, winRect.top)
    }

    private fun fireChangeIfNeeded() {
        if (needsNotify) {
            needsNotify = false
            onCropChange?.invoke()
        }
    }

    private fun fireChange() {
        needsNotify = false
        onCropChange?.invoke()
    }

    // ---------------- 裁剪区域映射 ----------------
    /** 裁剪框映射回源图像素坐标；无图或异常时返回 null */
    fun getCropRect(): RectF? {
        val s = src ?: return null
        if (!matrix.invert(inverse)) {
            return RectF(0f, 0f, s.width.toFloat(), s.height.toFloat())
        }
        val out = RectF()
        inverse.mapRect(out, winRect)   // 两参版本，避免覆盖 winRect
        val bw = s.width.toFloat()
        val bh = s.height.toFloat()
        out.left = out.left.coerceIn(0f, bw)
        out.top = out.top.coerceIn(0f, bh)
        out.right = out.right.coerceIn(0f, bw)
        out.bottom = out.bottom.coerceIn(0f, bh)
        if (out.width() < 1f) out.right = min(out.left + 1f, bw)
        if (out.height() < 1f) out.bottom = min(out.top + 1f, bh)
        return out
    }

    // ---------------- 绘制 ----------------
    override fun onDraw(canvas: Canvas) {
        val s = src
        if (s == null) {
            canvas.drawColor(Color.DKGRAY)
            canvas.drawText("请先选择图片", viewW / 2f, viewH / 2f, textPaint)
            return
        }
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(s, matrix, bitmapPaint)

        // 窗外四块变暗
        tmpRect.set(0f, 0f, viewW.toFloat(), winRect.top)
        canvas.drawRect(tmpRect, dimPaint)
        tmpRect.set(0f, winRect.bottom, viewW.toFloat(), viewH.toFloat())
        canvas.drawRect(tmpRect, dimPaint)
        tmpRect.set(0f, winRect.top, winRect.left, winRect.bottom)
        canvas.drawRect(tmpRect, dimPaint)
        tmpRect.set(winRect.right, winRect.top, viewW.toFloat(), winRect.bottom)
        canvas.drawRect(tmpRect, dimPaint)

        // 边框 + 三分线
        canvas.drawRect(winRect, borderPaint)
        for (i in 1..2) {
            val x = winRect.left + winRect.width() * i / 3f
            canvas.drawLine(x, winRect.top, x, winRect.bottom, gridPaint)
            val y = winRect.top + winRect.height() * i / 3f
            canvas.drawLine(winRect.left, y, winRect.right, y, gridPaint)
        }
    }

    companion object {
        private const val MAX_ZOOM = 5f
    }
}
