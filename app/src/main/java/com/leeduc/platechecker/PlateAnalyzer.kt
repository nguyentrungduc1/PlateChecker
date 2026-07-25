package com.leeduc.platechecker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Nhận dạng ký tự biển số bằng ML Kit Text Recognition (chạy offline trên máy),
 * sau đó xác định màu nền biển số (vàng / xanh dương / trắng) dựa trên vùng ảnh
 * bao quanh vùng chữ nhận diện được, rồi ghép hậu tố màu vào cuối chuỗi ký tự.
 *
 * Quy ước hậu tố:
 *   Trắng      -> T
 *   Vàng       -> V
 *   Xanh dương -> X
 *   Đỏ         -> (không có hậu tố, giữ nguyên chuỗi ký tự)
 */
object PlateAnalyzer {

    fun analyze(bitmap: Bitmap, callback: (String?) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Tiền xử lý riêng cho OCR (grayscale + tăng tương phản + khử nhiễu nhẹ).
        // Bitmap màu gốc vẫn được giữ nguyên để dùng cho bước nhận màu bên dưới,
        // vì grayscale sẽ phá hết thông tin màu cần thiết.
        val ocrInputBitmap = preprocessForOcr(bitmap)
        val image = InputImage.fromBitmap(ocrInputBitmap, 0)

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

                // Hợp nhất bounding box của tất cả block chữ để lấy vùng lấy màu biển số.
                // Toạ độ này khớp 1:1 với bitmap màu gốc vì tiền xử lý OCR không đổi kích thước ảnh.
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
                    "T"
                }

                callback(rawChars + colorSuffix)
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    /**
     * Tiền xử lý ảnh trước khi đưa vào ML Kit: chuyển xám (grayscale), tăng tương phản,
     * và khử nhiễu nhẹ (làm mượt nhẹ để giảm hạt nhiễu JPEG mà không làm mất nét chữ).
     * ML Kit đọc chữ chính xác hơn đáng kể trên ảnh đã được chuẩn hoá kiểu này so với
     * ảnh màu gốc có độ tương phản thấp / nhiễu do nén JPEG hoặc ánh sáng không đều.
     */
    private fun preprocessForOcr(source: Bitmap): Bitmap {
        // Khử nhiễu nhẹ: thu nhỏ rồi phóng to lại bằng nội suy song tuyến tính (bilinear).
        // Đây là cách làm mượt ảnh rẻ và nhanh, tương đương một lớp làm mờ Gaussian nhẹ,
        // đủ để giảm hạt nhiễu mà không cần viết vòng lặp convolution thủ công tốn CPU.
        val denoiseScale = 0.85f
        val smallW = max(1, (source.width * denoiseScale).toInt())
        val smallH = max(1, (source.height * denoiseScale).toInt())
        val shrunk = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val denoised = Bitmap.createScaledBitmap(shrunk, source.width, source.height, true)

        // Grayscale + tăng tương phản bằng ColorMatrix, vẽ qua Canvas (nhanh, không cần
        // duyệt từng pixel thủ công).
        val contrastFactor = 1.35f // >1 = tăng tương phản
        val translate = (-0.5f * contrastFactor + 0.5f) * 255f

        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f) // grayscale
        }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrastFactor, 0f, 0f, 0f, translate,
                0f, contrastFactor, 0f, 0f, translate,
                0f, 0f, contrastFactor, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(contrastMatrix)

        val result = Bitmap.createBitmap(denoised.width, denoised.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(denoised, 0f, 0f, paint)

        return result
    }

    /**
     * Xác định màu NỀN biển số (không phải màu trung bình cả vùng) rồi phân loại theo HSV.
     * Cách làm: lấy mẫu pixel trong vùng quanh chữ, sau đó LOẠI BỎ các pixel quá tối
     * (nhiều khả năng là nét chữ đen) và quá sáng (nhiều khả năng là vệt loá đèn flash),
     * chỉ giữ lại dải pixel ở giữa - đại diện cho màu nền thật của biển số - rồi lấy
     * TRUNG VỊ (median, không phải trung bình) trên tập đã lọc để giảm ảnh hưởng của
     * bóng đổ, viền, hoặc màu thân xe lọt vào rìa vùng cắt.
     */
    private fun classifyPlateColor(bitmap: Bitmap, textRect: Rect): String {
        // Giảm padding so với trước đây (0.6f) để tránh lấy lem sang cản trước/thân xe,
        // chỉ mở rộng vừa đủ để bắt được nền ngay sát chữ.
        val padX = (textRect.width() * 0.12f).toInt()
        val padY = (textRect.height() * 0.30f).toInt()

        val left = (textRect.left - padX).coerceIn(0, bitmap.width - 1)
        val top = (textRect.top - padY).coerceIn(0, bitmap.height - 1)
        val right = (textRect.right + padX).coerceIn(left + 1, bitmap.width)
        val bottom = (textRect.bottom + padY).coerceIn(top + 1, bitmap.height)

        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 0 || cropHeight <= 0) return "T"

        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

        val stepX = (cropWidth / 80).coerceAtLeast(1)
        val stepY = (cropHeight / 80).coerceAtLeast(1)

        data class Sample(val r: Int, val g: Int, val b: Int, val luma: Int)

        val samples = ArrayList<Sample>()
        var y = 0
        while (y < cropHeight) {
            var x = 0
            while (x < cropWidth) {
                val pixel = cropped.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                samples.add(Sample(r, g, b, luma))
                x += stepX
            }
            y += stepY
        }

        if (samples.isEmpty()) return "T"

        // Sắp xếp theo độ sáng để loại bỏ 2 đầu: dải tối nhất (chữ đen / bóng đổ) và
        // dải sáng nhất (vệt loá đèn flash), chỉ giữ lại dải giữa đại diện cho nền biển số.
        val sortedByLuma = samples.sortedBy { it.luma }
        val lowerCut = (sortedByLuma.size * 0.35f).toInt()
        val upperCut = (sortedByLuma.size * 0.90f).toInt()
        val backgroundSamples = sortedByLuma.subList(
            min(lowerCut, sortedByLuma.size - 1),
            max(upperCut, lowerCut + 1).coerceAtMost(sortedByLuma.size)
        )

        val filtered = if (backgroundSamples.isNotEmpty()) backgroundSamples else sortedByLuma

        // Trung vị từng kênh màu trên tập đã lọc, ổn định hơn trung bình khi còn sót outlier.
        val medianR = filtered.map { it.r }.sorted()[filtered.size / 2]
        val medianG = filtered.map { it.g }.sorted()[filtered.size / 2]
        val medianB = filtered.map { it.b }.sorted()[filtered.size / 2]

        val hsv = FloatArray(3)
        Color.RGBToHSV(medianR, medianG, medianB, hsv)
        val hue = hsv[0]        // 0-360
        val saturation = hsv[1] // 0-1
        val value = hsv[2]      // 0-1

        val hasEnoughColor = saturation > 0.30f

        return when {
            // Trắng: sáng và gần như không có màu (bão hòa thấp) -> ưu tiên xét trước
            value > 0.55f && saturation < 0.25f -> "T"
            // Đỏ: hue quanh mốc 0 độ (đỏ nằm ở 2 đầu vòng tròn màu: 0-15 hoặc 345-360)
            hasEnoughColor && (hue <= 15f || hue >= 345f) -> "" // không thêm hậu tố
            // Vàng: hue khoảng 30-75 độ, đủ bão hòa
            hasEnoughColor && hue in 30f..75f -> "V"
            // Xanh dương: hue khoảng 190-250 độ, đủ bão hòa
            hasEnoughColor && hue in 190f..250f -> "X"
            // Không rơi rõ vào trường hợp nào -> mặc định về trắng (an toàn hơn đoán bừa)
            else -> "T"
        }
    }
}
