package com.leeduc.platechecker

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.leeduc.platechecker.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastPhotoUri: Uri? = null
    private var lastPlateCropBitmap: Bitmap? = null
    private var isShowingCapturedPhoto = false

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            if (cameraGranted) startCamera() else {
                Toast.makeText(this, "Cần quyền Camera để chụp ảnh", Toast.LENGTH_LONG).show()
            }
        }

    // Chọn ảnh có sẵn từ thư viện (dùng Photo Picker hệ thống, không cần xin quyền đọc bộ nhớ)
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadImageFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val neededPermissions = mutableListOf(Manifest.permission.CAMERA)
        // Trên Android 9 (API 28) trở xuống cần quyền ghi bộ nhớ để lưu vào DCIM theo kiểu file cũ
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val allGranted = neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) startCamera() else requestPermissionsLauncher.launch(neededPermissions.toTypedArray())

        binding.btnCapture.setOnClickListener {
            if (isShowingCapturedPhoto) {
                showCameraView()
            } else {
                takePhoto()
            }
        }

        binding.btnLoadImage.setOnClickListener {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.btnCheck.setOnClickListener { runCheck() }

        // "Duyệt": vào thẳng trang vos.vetc.com.vn, không chờ OCR, kèm ảnh biển số
        // (nếu đã có) để xem đối chiếu ngay trong lúc thao tác trên web.
        binding.btnBrowse.setOnClickListener { openBrowserDirectly() }
    }

    /** Quay lại xem camera trực tiếp để canh và chụp tấm tiếp theo. */
    private fun showCameraView() {
        isShowingCapturedPhoto = false
        binding.imgPreview.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.guideOverlay.visibility = android.view.View.VISIBLE
        binding.btnCapture.text = "Chụp ảnh"
        binding.btnCheck.isEnabled = false
        binding.txtResult.text = ""
        lastPlateCropBitmap = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Ép Preview và ImageCapture dùng cùng tỉ lệ khung hình, để khung ngắm
            // trên preview khớp đúng với vùng cắt trên ảnh chụp thật.
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Ảnh chất lượng cao + luôn bật đèn flash khi chụp
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setFlashMode(ImageCapture.FLASH_MODE_ON)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi bind camera", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val fileName = "PLATE_$name.jpg"

        val outputOptions: ImageCapture.OutputFileOptions

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: lưu vào DCIM/PlateChecker qua MediaStore (bắt buộc theo scoped storage)
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PlateChecker")
            }
            outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else {
            // Android 9 trở xuống: ghi trực tiếp vào thư mục DCIM công khai
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val targetDir = File(dcimDir, "PlateChecker")
            if (!targetDir.exists()) targetDir.mkdirs()
            val photoFile = File(targetDir, fileName)
            outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        }

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Chụp ảnh thất bại", exc)
                    Toast.makeText(baseContext, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri ?: run {
                        Toast.makeText(baseContext, "Lỗi: không lấy được đường dẫn ảnh", Toast.LENGTH_SHORT).show()
                        return
                    }
                    lastPhotoUri = uri

                    // Quét lại media store cho thiết bị cũ để ảnh hiện ngay trong Gallery
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    }

                    // Xoay ảnh đúng chiều theo EXIF, đóng dấu ngày giờ chụp vào góc dưới bên phải,
                    // rồi ghi đè lại file gốc một lần (ảnh lưu trong DCIM sẽ có sẵn dấu thời gian)
                    val rotatedBitmap = fixOrientation(uri)
                    val stampedBitmap = addTimestampWatermark(rotatedBitmap)
                    saveBitmapToUri(stampedBitmap, uri)

                    // Chỉ cắt đúng vùng nằm trong khung ngắm để đưa vào OCR, không quét cả ảnh
                    val plateCrop = cropToGuideRegion(stampedBitmap)
                    lastPlateCropBitmap = plateCrop

                    Toast.makeText(baseContext, "Đã lưu vào DCIM/PlateChecker", Toast.LENGTH_SHORT).show()

                    showCapturedResult(plateCrop)
                }
            }
        )
    }

    /**
     * Tải ảnh có sẵn từ thư viện thay cho chụp trực tiếp. Ảnh này chính là ảnh do
     * app tự chụp trước đó (lưu trong DCIM/PlateChecker) nên vùng biển số LUÔN nằm
     * đúng vị trí khung ngắm cố định - áp dụng lại đúng phép cắt giống lúc chụp
     * trực tiếp, không dùng nguyên cả ảnh để OCR.
     */
    private fun loadImageFromUri(uri: Uri) {
        val rotated = fixOrientation(uri)
        val plateCrop = cropToGuideRegion(rotated)
        lastPlateCropBitmap = plateCrop
        showCapturedResult(plateCrop)
    }

    private fun showCapturedResult(bitmap: Bitmap) {
        binding.previewView.visibility = android.view.View.GONE
        binding.guideOverlay.visibility = android.view.View.GONE
        binding.imgPreview.visibility = android.view.View.VISIBLE
        binding.imgPreview.setImageBitmap(bitmap)

        isShowingCapturedPhoto = true
        binding.btnCapture.text = "Chụp lại"
        binding.btnCheck.isEnabled = true
        binding.txtResult.text = ""
    }

    /**
     * Đọc góc xoay từ EXIF và xoay vật lý pixel ảnh cho đúng chiều (chưa ghi file,
     * việc ghi file sẽ làm một lần duy nhất sau khi đã đóng dấu thời gian).
     */
    private fun fixOrientation(uri: Uri): Bitmap {
        val orientation = contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        if (rotationDegrees == 0) return original

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    /**
     * Vẽ dấu ngày giờ chụp vào góc dưới bên phải của ảnh, kiểu dấu thời gian của
     * máy ảnh cũ - có nền mờ phía sau chữ để luôn đọc được dù nền ảnh sáng hay tối.
     */
    private fun addTimestampWatermark(bitmap: Bitmap): Bitmap {
        val stamped = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(stamped)

        val timestampText = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN"))
            .format(System.currentTimeMillis())

        val textSizePx = stamped.width * 0.032f
        val padding = stamped.width * 0.02f

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFC400")
            textSize = textSizePx
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        }

        val textWidth = textPaint.measureText(timestampText)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top

        val boxRight = stamped.width - padding
        val boxBottom = stamped.height - padding
        val boxLeft = boxRight - textWidth - padding
        val boxTop = boxBottom - textHeight - padding * 0.5f

        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#66000000")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRoundRect(
            android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom),
            12f, 12f, bgPaint
        )

        val textX = boxRight - padding / 2f - textWidth
        val textY = boxBottom - padding / 2f - fontMetrics.bottom
        canvas.drawText(timestampText, textX, textY, textPaint)

        return stamped
    }

    private fun saveBitmapToUri(bitmap: Bitmap, uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không ghi được ảnh đã đóng dấu thời gian", e)
        }
    }

    /**
     * Cắt đúng vùng nằm trong khung ngắm (PlateGuideOverlayView) ra khỏi ảnh gốc,
     * dựa theo tỉ lệ phần trăm đã dùng để vẽ khung, để phần OCR sau này chỉ nhìn
     * thấy vùng biển số thay vì toàn bộ bức ảnh.
     */
    private fun cropToGuideRegion(bitmap: Bitmap): Bitmap {
        val frac = binding.guideOverlay.getGuideRectFraction()

        val left = (frac.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (frac.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (frac.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (frac.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /** Lưu tạm ảnh biển số vào cache của app, dùng để truyền qua WebViewActivity (tránh Intent quá lớn). */
    private fun savePlateImageToCache(bitmap: Bitmap): String? {
        return try {
            val file = File(cacheDir, "plate_preview.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Không lưu được ảnh xem trước cho WebView", e)
            null
        }
    }

    private fun runCheck() {
        val plateBitmap = lastPlateCropBitmap
        if (plateBitmap == null) {
            Toast.makeText(this, "Chưa có ảnh để kiểm tra", Toast.LENGTH_SHORT).show()
            return
        }

        binding.txtResult.text = "Đang phân tích biển số..."
        binding.btnCheck.isEnabled = false

        PlateAnalyzer.analyze(plateBitmap) { result ->
            binding.btnCheck.isEnabled = true
            if (result == null) {
                binding.txtResult.text = "Không nhận diện được biển số. Thử chụp lại, canh biển số vào đúng khung vàng."
                return@analyze
            }

            binding.txtResult.text = "Kết quả: $result"

            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_PLATE_CODE, result)
            savePlateImageToCache(plateBitmap)?.let { path ->
                intent.putExtra(WebViewActivity.EXTRA_PLATE_IMAGE_PATH, path)
            }
            startActivity(intent)
        }
    }

    /** Nút "Duyệt": vào thẳng trang web, không chờ OCR. */
    private fun openBrowserDirectly() {
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra(WebViewActivity.EXTRA_PLATE_CODE, "")
        lastPlateCropBitmap?.let { bmp ->
            savePlateImageToCache(bmp)?.let { path ->
                intent.putExtra(WebViewActivity.EXTRA_PLATE_IMAGE_PATH, path)
            }
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isShowingCapturedPhoto) {
            showCameraView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PlateChecker"
    }
}
