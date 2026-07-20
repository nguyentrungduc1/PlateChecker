package com.leeduc.platechecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastPhotoFile: File? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Cần quyền Camera để chụp ảnh", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

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

            // Ảnh chất lượng cao: ưu tiên chất lượng thay vì tốc độ, JPEG quality tối đa
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
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

        val picturesDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "PlateChecker")
        if (!picturesDir.exists()) picturesDir.mkdirs()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(picturesDir, "PLATE_$name.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Chụp ảnh thất bại", exc)
                    Toast.makeText(baseContext, "Chụp ảnh thất bại: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastPhotoFile = photoFile
                    Toast.makeText(baseContext, "Đã lưu: ${photoFile.absolutePath}", Toast.LENGTH_SHORT).show()

                    val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                    binding.previewView.visibility = android.view.View.GONE
                    binding.imgPreview.visibility = android.view.View.VISIBLE
                    binding.imgPreview.setImageBitmap(bmp)

                    binding.btnCheck.isEnabled = true
                    binding.txtResult.text = ""
                }
            }
        )
    }

    private fun runCheck() {
        val file = lastPhotoFile
        if (file == null) {
            Toast.makeText(this, "Chưa có ảnh để kiểm tra", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap: Bitmap = BitmapFactory.decodeFile(file.absolutePath)
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
