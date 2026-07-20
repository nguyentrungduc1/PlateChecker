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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.leeduc.platechecker.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastPhotoUri: Uri? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            if (cameraGranted) startCamera() else {
                Toast.makeText(this, "Cần quyền Camera để chụp ảnh", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val neededPermissions = mutableListOf(Manifest.permission.CAMERA)
        // Trên Android 9 (API 28) trở xuống cần quyền ghi bộ nhớ để lưu vào DCIM theo kiểu file cũ
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val allGranted = neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) startCamera() else requestPermissionsLauncher.launch(neededPermissions.toTypedArray())

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnCheck.setOnClickListener { runCheck() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Ảnh chất lượng cao + luôn bật đèn flash khi chụp
            imageCapture = ImageCapture.Builder()
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
            val targetDir = java.io.File(dcimDir, "PlateChecker")
            if (!targetDir.exists()) targetDir.mkdirs()
            val photoFile = java.io.File(targetDir, fileName)
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

                    // Sửa lỗi ảnh bị xoay: đọc EXIF, xoay lại pixel cho đúng chiều rồi ghi đè file
                    val correctedBitmap = correctOrientation(uri)

                    Toast.makeText(baseContext, "Đã lưu vào DCIM/PlateChecker", Toast.LENGTH_SHORT).show()

                    binding.previewView.visibility = android.view.View.GONE
                    binding.imgPreview.visibility = android.view.View.VISIBLE
                    binding.imgPreview.setImageBitmap(correctedBitmap)

                    binding.btnCheck.isEnabled = true
                    binding.txtResult.text = ""
                }
            }
        )
    }

    /**
     * Đọc góc xoay từ EXIF, xoay vật lý pixel ảnh cho đúng chiều, rồi ghi đè lại
     * file gốc (chất lượng JPEG tối đa) để ảnh trong DCIM và bước OCR đều đúng chiều.
     */
    private fun correctOrientation(uri: Uri): Bitmap {
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
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)

        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không ghi đè được ảnh đã xoay", e)
        }

        return rotated
    }

    private fun runCheck() {
        val uri = lastPhotoUri
        if (uri == null) {
            Toast.makeText(this, "Chưa có ảnh để kiểm tra", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap: Bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: run {
                Toast.makeText(this, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
                return
            }

        binding.txtResult.text = "Đang phân tích biển số..."
        binding.btnCheck.isEnabled = false

        PlateAnalyzer.analyze(bitmap) { result ->
            binding.btnCheck.isEnabled = true
            if (result == null) {
                binding.txtResult.text = "Không nhận diện được biển số. Thử chụp lại gần và rõ hơn."
                return@analyze
            }

            binding.txtResult.text = "Kết quả: $result"

            // Mở WebView, tự điền vào ô đầu tiên của trang vos.vetc.com.vn
            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_PLATE_CODE, result)
            startActivity(intent)
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
