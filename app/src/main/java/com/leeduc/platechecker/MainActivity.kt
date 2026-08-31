package com.leeduc.platechecker

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
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
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
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
    // Chỉ dùng để biết "vừa đi từ màn hình duyệt web quay lại" - khác với việc
    // quay lại app sau khi chọn ảnh (cả 2 đều gây onPause/onResume như nhau,
    // nên không thể dùng chung isShowingCapturedPhoto để phân biệt).
    private var pendingResetAfterWebView = false
    private var flashEnabled = false

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
        flashEnabled = getPreferences(MODE_PRIVATE).getBoolean(PREF_FLASH_ENABLED, false)
        updateFlashUi()

        val neededPermissions = mutableListOf(Manifest.permission.CAMERA)
        // Trên Android 9 (API 28) trở xuống cần quyền ghi bộ nhớ để lưu vào DCIM theo kiểu file cũ
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val allGranted = neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) startCamera() else requestPermissionsLauncher.launch(neededPermissions.toTypedArray())

        preloadVosPageInBackground()

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

        binding.btnRegistrationCheck.setOnClickListener { runCheck(VOS_REGISTER_URL) }
        binding.btnCheck.setOnClickListener { runCheck(VOS_HOME_URL) }

        // "Duyệt": vào thẳng trang vos.vetc.com.vn, không chờ OCR, kèm ảnh biển số
        // (nếu đã có) để xem đối chiếu ngay trong lúc thao tác trên web.
        binding.btnBrowse.setOnClickListener { openBrowserDirectly() }

        binding.btnFlashToggle.setOnClickListener {
            flashEnabled = !flashEnabled
            getPreferences(MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_FLASH_ENABLED, flashEnabled)
                .apply()
            updateFlashUi()
        }
    }

    private fun updateFlashUi() {
        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        binding.btnFlashToggle.text = if (flashEnabled) "⚡ Flash: Bật" else "⚡ Flash: Tắt"
    }

    /** Quay lại xem camera trực tiếp để canh và chụp tấm tiếp theo. */
    private fun showCameraView() {
        isShowingCapturedPhoto = false
        binding.imgPreview.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.guideOverlay.visibility = android.view.View.VISIBLE
        binding.btnCapture.text = "Chụp ảnh"
        binding.btnCheck.isEnabled = false
        binding.btnRegistrationCheck.isEnabled = false
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

            // Ảnh chất lượng cao + đèn flash bật/tắt theo lựa chọn của người dùng
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(92)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
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
        val guideRectFraction = binding.guideOverlay.getGuideRectFraction()

        binding.btnCapture.isEnabled = false

        imageCapture.takePicture(
            cameraExecutor,
            object : OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Chụp ảnh thất bại", exc)
                    runOnUiThread {
                        binding.btnCapture.isEnabled = true
                        Toast.makeText(baseContext, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotatedBitmap = try {
                        val capturedBitmap = image.toBitmap()
                        rotateBitmap(capturedBitmap, image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        Log.e(TAG, "Không xử lý được ảnh vừa chụp", e)
                        runOnUiThread {
                            binding.btnCapture.isEnabled = true
                            Toast.makeText(baseContext, "Không xử lý được ảnh vừa chụp: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        return
                    } finally {
                        image.close()
                    }

                    // OCR dùng ảnh vừa chụp trong RAM, chưa đóng dấu và chưa qua vòng nén JPEG nào.
                    val plateCrop = cropToGuideRegion(rotatedBitmap, guideRectFraction)

                    runOnUiThread {
                        lastPlateCropBitmap = plateCrop
                        binding.btnCapture.isEnabled = true
                        showCapturedResult(plateCrop)
                    }

                    // Ảnh lưu DCIM được đóng dấu và nén JPEG đúng một lần, chạy sau khi UI đã có phản hồi.
                    val stampedBitmap = addTimestampWatermark(rotatedBitmap)
                    val savedUri = saveBitmapToGallery(stampedBitmap, fileName)
                    lastPhotoUri = savedUri

                    runOnUiThread {
                        val message = if (savedUri != null) {
                            "Đã lưu vào DCIM/PlateChecker"
                        } else {
                            "Đã chụp ảnh, nhưng chưa lưu được vào DCIM"
                        }
                        Toast.makeText(baseContext, message, Toast.LENGTH_SHORT).show()
                    }
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
        try {
            val rotated = fixOrientation(uri)
            val plateCrop = cropToGuideRegion(rotated)
            lastPlateCropBitmap = plateCrop
            showCapturedResult(plateCrop)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi load ảnh từ thư viện", e)
            Toast.makeText(this, "Không đọc được ảnh đã chọn: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCapturedResult(bitmap: Bitmap) {
        binding.previewView.visibility = android.view.View.GONE
        binding.guideOverlay.visibility = android.view.View.GONE
        binding.imgPreview.visibility = android.view.View.VISIBLE
        binding.imgPreview.setImageBitmap(bitmap)

        isShowingCapturedPhoto = true
        binding.btnCapture.text = "Chụp lại"
        binding.btnCheck.isEnabled = true
        binding.btnRegistrationCheck.isEnabled = true
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
        // filter=false: xoay đúng 90/180/270 độ không cần nội suy (mỗi pixel khớp
        // chính xác 1-1 sang vị trí mới), dùng nearest-neighbor cho kết quả sắc nét
        // tuyệt đối, tránh làm mờ nhẹ không cần thiết như khi filter=true.
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, false)
    }

    /**
     * Xoay vật lý pixel ảnh vừa nhận từ CameraX theo metadata của ImageProxy.
     */
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    /**
     * Vẽ dấu ngày giờ chụp vào góc dưới bên trái của ảnh, kiểu dấu thời gian của
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

        val boxLeft = padding
        val boxBottom = stamped.height - padding
        val boxRight = boxLeft + textWidth + padding
        val boxTop = boxBottom - textHeight - padding * 0.5f

        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#66000000")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRoundRect(
            android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom),
            12f, 12f, bgPaint
        )

        val textX = boxLeft + padding / 2f
        val textY = boxBottom - padding / 2f - fontMetrics.bottom
        canvas.drawText(timestampText, textX, textY, textPaint)

        return stamped
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: lưu vào DCIM/PlateChecker qua MediaStore (scoped storage).
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PlateChecker")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return null

                val saved = contentResolver.openOutputStream(uri, "w")?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } == true

                if (!saved) {
                    contentResolver.delete(uri, null, null)
                    return null
                }

                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                uri
            } else {
                // Android 9 trở xuống: ghi trực tiếp vào thư mục DCIM công khai.
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val targetDir = File(dcimDir, "PlateChecker")
                if (!targetDir.exists()) targetDir.mkdirs()
                val photoFile = File(targetDir, fileName)
                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                val uri = Uri.fromFile(photoFile)
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không lưu được ảnh đã đóng dấu thời gian", e)
            null
        }
    }

    /**
     * Cắt đúng vùng nằm trong khung ngắm (PlateGuideOverlayView) ra khỏi ảnh gốc,
     * dựa theo tỉ lệ phần trăm đã dùng để vẽ khung, để phần OCR sau này chỉ nhìn
     * thấy vùng biển số thay vì toàn bộ bức ảnh.
     */
    private fun cropToGuideRegion(bitmap: Bitmap): Bitmap {
        return cropToGuideRegion(bitmap, binding.guideOverlay.getGuideRectFraction())
    }

    private fun cropToGuideRegion(bitmap: Bitmap, frac: RectF): Bitmap {
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

    private fun runCheck(targetUrl: String) {
        val plateBitmap = lastPlateCropBitmap
        if (plateBitmap == null) {
            Toast.makeText(this, "Chưa có ảnh để kiểm tra", Toast.LENGTH_SHORT).show()
            return
        }

        binding.txtResult.text = "Đang phân tích biển số..."
        binding.btnCheck.isEnabled = false
        binding.btnRegistrationCheck.isEnabled = false

        PlateAnalyzer.analyze(plateBitmap) { result ->
            binding.btnCheck.isEnabled = true
            binding.btnRegistrationCheck.isEnabled = true
            if (result == null) {
                binding.txtResult.text = "Không nhận diện được biển số. Thử chụp lại, canh biển số vào đúng khung vàng."
                return@analyze
            }

            binding.txtResult.text = "Kết quả: $result"

            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_PLATE_CODE, result)
            intent.putExtra(WebViewActivity.EXTRA_TARGET_URL, targetUrl)
            savePlateImageToCache(plateBitmap)?.let { path ->
                intent.putExtra(WebViewActivity.EXTRA_PLATE_IMAGE_PATH, path)
            }
            pendingResetAfterWebView = true
            startActivity(intent)
        }
    }

    /** Nút "Duyệt": vào thẳng trang web, không chờ OCR. */
    private fun openBrowserDirectly() {
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra(WebViewActivity.EXTRA_PLATE_CODE, "")
        intent.putExtra(WebViewActivity.EXTRA_TARGET_URL, VOS_HOME_URL)
        lastPlateCropBitmap?.let { bmp ->
            savePlateImageToCache(bmp)?.let { path ->
                intent.putExtra(WebViewActivity.EXTRA_PLATE_IMAGE_PATH, path)
            }
        }
        pendingResetAfterWebView = true
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Chỉ tự quay về camera khi vừa đi từ WebView về, KHÔNG áp dụng khi vừa
        // chọn ảnh xong từ trình chọn ảnh (trường hợp đó cũng gây onPause/onResume
        // giống hệt, nếu dùng chung điều kiện sẽ vô tình xoá mất ảnh vừa load lên).
        if (pendingResetAfterWebView) {
            pendingResetAfterWebView = false
            showCameraView()
        }
    }

    /**
     * Tải trước trang VOS bằng 1 WebView ẩn (1dp, invisible) ngay khi mở app - trong
     * lúc bạn đang canh khung/chụp ảnh/chờ OCR, trang này đã âm thầm tải ở nền.
     * WebView dùng chung 1 bộ nhớ đệm (cache) trong cùng app, nên khi màn hình Check
     * thật sự mở trang này sau đó, nhiều tài nguyên (JS, CSS...) đã có sẵn trong cache,
     * không cần tải lại từ đầu -> mở nhanh hơn. Không đảm bảo nhanh tuyệt đối vì còn
     * phụ thuộc tốc độ mạng thực tế và cách trang VOS tự đặt hạn cache cho từng file.
     */
    private fun preloadVosPageInBackground() {
        try {
            val preload = binding.webViewPreload
            preload.settings.javaScriptEnabled = true
            preload.settings.domStorageEnabled = true
            preload.settings.databaseEnabled = true
            preload.loadUrl(VOS_HOME_URL)
        } catch (e: Exception) {
            Log.e(TAG, "Không tải trước được trang VOS", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        binding.webViewPreload.destroy()
    }

    companion object {
        private const val TAG = "PlateChecker"
        private const val PREF_FLASH_ENABLED = "flash_enabled"
        private const val VOS_HOME_URL = "https://vos.vetc.com.vn/"
        private const val VOS_REGISTER_URL = "https://vos.vetc.com.vn/search-info-register"
    }
}
