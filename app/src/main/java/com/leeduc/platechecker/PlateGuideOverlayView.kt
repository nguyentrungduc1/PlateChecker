package com.leeduc.platechecker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Vẽ khung ngắm hình chữ nhật theo đúng tỉ lệ biển số ô tô Việt Nam (1 dòng,
 * khoảng 520x110mm => tỉ lệ ~4.7:1), giúp người dùng canh biển số vào giữa khung
 * trước khi chụp. Toạ độ khung được tính theo TỈ LỆ PHẦN TRĂM (0f..1f) so với
 * kích thước view, để MainActivity có thể dùng lại đúng tỉ lệ này khi cắt ảnh
 * đã chụp (giả định camera preview không bị crop lệch so với ảnh chụp thật).
 */
class PlateGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Khung chiếm 78% chiều rộng, tỉ lệ biển số ô tô ~4.7:1, căn giữa màn hình
        const val WIDTH_FRACTION = 0.78f
        const val ASPECT_RATIO = 4.7f // width / height
        const val CENTER_Y_FRACTION = 0.5f
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val framePaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    /**
     * Trả về vùng khung ngắm theo tỉ lệ phần trăm (left, top, right, bottom trong 0f..1f).
     * Nếu view chưa được đo kích thước (width/height = 0, ví dụ bị gọi quá sớm hoặc
     * đang ở trạng thái GONE trước khi layout lần đầu hoàn tất), trả về nguyên khung
     * 0..1 (toàn bộ ảnh) để nơi gọi không bị chia cho 0 / tạo bitmap kích thước âm.
     */
    fun getGuideRectFraction(): RectF {
        if (width <= 0 || height <= 0) {
            return RectF(0f, 0f, 1f, 1f)
        }

        val guideWidthFraction = WIDTH_FRACTION
        val guideHeightFraction = (width.toFloat() * guideWidthFraction / ASPECT_RATIO) / height.toFloat()

        val left = (1f - guideWidthFraction) / 2f
        val right = left + guideWidthFraction
        val top = (CENTER_Y_FRACTION - guideHeightFraction / 2f).coerceIn(0f, 1f)
        val bottom = (CENTER_Y_FRACTION + guideHeightFraction / 2f).coerceIn(0f, 1f)

        return RectF(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val frac = getGuideRectFraction()
        val rect = RectF(
            frac.left * width,
            frac.top * height,
            frac.right * width,
            frac.bottom * height
        )

        // Làm tối phần ngoài khung để nổi bật vùng cần canh biển số
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)

        canvas.drawRect(rect, framePaint)

        // Vẽ 4 góc nổi bật kiểu khung ngắm mã QR
        val cornerLen = rect.width() * 0.08f
        // Top-left
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLen, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLen, cornerPaint)
        // Top-right
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLen, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLen, cornerPaint)
        // Bottom-left
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLen, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLen, cornerPaint)
        // Bottom-right
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLen, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLen, cornerPaint)
    }
}
