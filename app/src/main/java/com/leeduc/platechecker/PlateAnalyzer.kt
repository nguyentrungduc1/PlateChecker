package com.leeduc.platechecker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Nhận dạng ký tự biển số bằng ML Kit Text Recognition (chạy offline trên máy),
 * sau đó xác định màu nền biển số (vàng / xanh dương / trắng) dựa trên vùng ảnh
 * bao quanh vùng chữ nhận diện được, rồi ghép hậu tố màu vào cuối chuỗi ký tự.
 *
 * Quy ước hậu tố:
 *   Vàng      -> V
 *   Xanh dương -> X
 *   Trắng     -> T
 */
object PlateAnalyzer {

    fun analyze(bitmap: Bitmap, callback: (String?) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.textBlocks.isEmpty()) {
                    callback(null)
                    return@addOnSuccessListener
                }

                // Gom toàn bộ ký tự chữ/số nhận được, bỏ dấu cách, dấu gạch, dấu chấm...
                val rawChars = visionText.text
                    .uppercase()
                    .filter { it.isLetterOrDigit() }

                if (rawChars.isBlank()) {
                    callback(null)
                    return@addOnSuccessListener
                }

                // Hợp nhất bounding box của tất cả block chữ để lấy vùng lấy màu biển số
                var unionRect: Rect? = null
                for (block in visionText.textBlocks) {
                    val box = block.boundingBox ?: continue
                    unionRect = if (unionRect == null) Rect(box) else {
                        unionRect.union(box)
                        unionRect
                    }
                }

                val colorSuffix = if (unionRect != null) {
                    classifyPlateColor(bitmap, unionRect)
                } else {
                    // Không xác định được vùng -> mặc định để trắng, người dùng có thể sửa tay
                    'T'
                }

                callback(rawChars + colorSuffix)
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    /**
     * Lấy màu trung bình của vùng chữ (mở rộng nhẹ ra ngoài để bắt được nền biển số
     * thay vì chỉ chữ đen), rồi phân loại theo HSV.
     */
    private fun classifyPlateColor(bitmap: Bitmap, textRect: Rect): Char {
        // Mở rộng vùng cắt ra khoảng 15% mỗi chiều để lấy được nền xung quanh chữ
        val padX = (textRect.width() * 0.15f).toInt()
        val padY = (textRect.height() * 0.6f).toInt()

        val left = (textRect.left - padX).coerceIn(0, bitmap.width - 1)
        val top = (textRect.top - padY).coerceIn(0, bitmap.height - 1)
        val right = (textRect.right + padX).coerceIn(left + 1, bitmap.width)
        val bottom = (textRect.bottom + padY).coerceIn(top + 1, bitmap.height)

        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 0 || cropHeight <= 0) return 'T'

        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L

        // Lấy mẫu để tránh duyệt từng pixel nếu ảnh lớn
        val stepX = (cropWidth / 60).coerceAtLeast(1)
        val stepY = (cropHeight / 60).coerceAtLeast(1)

        var y = 0
        while (y < cropHeight) {
            var x = 0
            while (x < cropWidth) {
                val pixel = cropped.getPixel(x, y)
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0L) return 'T'

        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()

        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hue = hsv[0]        // 0-360
        val saturation = hsv[1] // 0-1
        val value = hsv[2]      // 0-1

        return when {
            // Trắng: sáng và gần như không có màu (bão hòa thấp)
            value > 0.6f && saturation < 0.25f -> 'T'
            // Vàng: hue khoảng 35-70 độ, đủ bão hòa
            hue in 30f..75f && saturation > 0.25f -> 'V'
            // Xanh dương: hue khoảng 190-250 độ
            hue in 170f..260f -> 'X'
            // Mặc định: chọn theo màu gần nhất trong 3 lựa chọn dựa trên kênh trội
            else -> when {
                avgB > avgR && avgB > avgG -> 'X'
                avgR > 180 && avgG > 150 && avgB < 120 -> 'V'
                else -> 'T'
            }
        }
    }
}
