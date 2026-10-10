package com.leeduc.platechecker

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.leeduc.platechecker.databinding.ActivityWebviewBinding
import java.io.File
import java.io.FileOutputStream

/**
 * WebView đóng vai trò như một "Chrome thu nhỏ" bên trong app:
 * - Dùng chung engine Chromium như Chrome ngoài, nên đăng nhập, mật khẩu, OTP,
 *   phiên làm việc (session cookie) đều hoạt động y hệt trình duyệt thật.
 * - Cookie được bật lưu vĩnh viễn nên phiên đăng nhập giữ nguyên ~7 tiếng như quy định.
 * - Có trình quản lý mật khẩu riêng (CredentialStore, mã hoá bằng Android Keystore),
 *   hoạt động giống Chrome: khi bạn tự tay đăng nhập lần đầu, app sẽ hỏi "Lưu mật khẩu?";
 *   từ lần sau sẽ tự điền tài khoản/mật khẩu đã lưu. Chưa lưu thì bạn tự gõ như bình thường.
 * - Khi gặp màn OTP: không tự động làm gì, để người dùng tự nhập mã từ SMS.
 * - Khi đã đăng nhập xong: tự điền biển số vào ô tìm kiếm.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding
    private var pendingPlateCode: String = ""
    private var targetUrl: String = TARGET_URL

    private val pickPriceImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) saveCustomPriceImage(uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingPlateCode = intent.getStringExtra(EXTRA_PLATE_CODE) ?: ""
        targetUrl = intent.getStringExtra(EXTRA_TARGET_URL) ?: TARGET_URL

        val plateImagePath = intent.getStringExtra(EXTRA_PLATE_IMAGE_PATH)
        if (plateImagePath != null) {
            val plateBitmap = android.graphics.BitmapFactory.decodeFile(plateImagePath)
            if (plateBitmap != null) binding.imgPlate.setImageBitmap(plateBitmap)
        }

        val webView: WebView = binding.webView

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.userAgentString = webView.settings.userAgentString?.replace("; wv", "")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Cầu nối để trang web báo về Android khi người dùng tự tay bấm nút đăng nhập
        webView.addJavascriptInterface(CredentialBridge(), "AndroidCredentialBridge")
        // Cầu nối riêng để nhận kết quả gọi API tra đăng kiểm (biển số + số khung)
        webView.addJavascriptInterface(RegistryBridge(), "AndroidRegistryBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()

                val savedUser = CredentialStore.getUsername(this@WebViewActivity)
                val savedPass = CredentialStore.getPassword(this@WebViewActivity)

                if (url?.contains("vos.vetc.com.vn") == true) {
                    runAutoFillScript(webView, pendingPlateCode, savedUser ?: "", savedPass ?: "")
                }
            }
        }

        webView.loadUrl(targetUrl)

        binding.btnRegistryCheck.setOnClickListener { openRegistrySite() }
        binding.btnManageCredentials.setOnClickListener { showManageCredentialsDialog() }

        buildPriceTable()
        updatePriceTableDisplay()
        binding.btnPriceList.setOnClickListener { togglePriceOverlay(show = true) }
        binding.btnClosePriceList.setOnClickListener { togglePriceOverlay(show = false) }
        binding.btnUploadPriceImage.setOnClickListener {
            pickPriceImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnDeletePriceImage.setOnClickListener { deleteCustomPriceImage() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.priceOverlay.visibility == android.view.View.VISIBLE) {
                    togglePriceOverlay(show = false)
                } else {
                    // Bỏ qua lịch sử điều hướng của WebView: bấm back sẽ thoát thẳng
                    // về màn hình camera, không lùi qua từng trang web đã ghé.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** Bật/tắt lớp phủ bảng giá, đồng thời ẩn/hiện 2 nút nổi (Giá, Quản lý mật khẩu) cho gọn màn hình. */
    private fun togglePriceOverlay(show: Boolean) {
        if (show) updatePriceTableDisplay()
        binding.priceOverlay.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        val buttonsVisibility = if (show) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnRegistryCheck.visibility = buttonsVisibility
        binding.btnPriceList.visibility = buttonsVisibility
        binding.btnManageCredentials.visibility = buttonsVisibility
    }

    /**
     * Đọc biển số ĐANG HIỂN THỊ trong ô tìm kiếm trên trang VOS (không dùng lại chuỗi
     * OCR trong app, vì OCR có thể sai và bạn có thể đã tự sửa tay), gọi thẳng API
     * thật của VETC (/api/360/registration-info) bằng fetch() ngay trong WebView
     * (tận dụng đúng cookie phiên đăng nhập hiện có) để lấy biển số + số khung.
     *
     * Trang đăng kiểm bị Cloudflare chặn khi mở trong WebView (đã xác nhận qua thực
     * tế test: lỗi SSL mã 3, nhưng Chrome thật mở bình thường) - nên không điền vào
     * WebView, mà: tự copy LẦN LƯỢT 6 số cuối số khung rồi đến biển số vào clipboard,
     * sau đó mới mở Chrome thật tới trang đăng kiểm.
     */
    private fun openRegistrySite() {
        Toast.makeText(this, "Đang lấy biển số + số khung từ trang VOS...", Toast.LENGTH_SHORT).show()

        val js = """
            (async function() {
                function reportError(message) {
                    if (window.AndroidRegistryBridge) {
                        window.AndroidRegistryBridge.onRegistryDataExtracted('', '', message);
                    }
                }
                function reportSuccess(plateRegistry, frameNumber) {
                    if (window.AndroidRegistryBridge) {
                        window.AndroidRegistryBridge.onRegistryDataExtracted(plateRegistry || '', frameNumber || '', '');
                    }
                }
                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    return style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
                }
                function getCurrentPlateInputValue() {
                    var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));
                    var visible = inputs.filter(isVisible);
                    for (var i = 0; i < visible.length; i++) {
                        var el = visible[i];
                        var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '')).toLowerCase();
                        if ((hint.indexOf('bien') !== -1 || hint.indexOf('etag') !== -1) && el.value) {
                            return el.value;
                        }
                    }
                    for (var j = 0; j < visible.length; j++) {
                        var el2 = visible[j];
                        var typeOk = !el2.type || ['text', 'search', 'tel', ''].indexOf(el2.type) !== -1;
                        if (typeOk && el2.value) return el2.value;
                    }
                    return '';
                }

                // Trang VOS xác thực API bằng token trong localStorage (không chỉ cookie).
                // Token nằm ở key "vetc-utilities" -> userToken.access_token.
                function getAccessToken() {
                    try {
                        var raw = localStorage.getItem('vetc-utilities');
                        if (!raw) return '';
                        var parsed = JSON.parse(raw);
                        return (parsed && parsed.userToken && parsed.userToken.access_token) || '';
                    } catch (e) {
                        return '';
                    }
                }

                // Dò tìm browser_id / browser_ip trong toàn bộ localStorage (không đoán đúng
                // tên key cố định) - quét mọi key, thử parse JSON, tìm đệ quy 2 trường này.
                function findFieldDeep(obj, fieldName, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 4) return null;
                    if (obj[fieldName] !== undefined && obj[fieldName] !== null) return obj[fieldName];
                    for (var key in obj) {
                        if (!obj.hasOwnProperty(key)) continue;
                        var val = obj[key];
                        if (val && typeof val === 'object') {
                            var found = findFieldDeep(val, fieldName, depth + 1);
                            if (found !== null) return found;
                        }
                    }
                    return null;
                }
                function scanLocalStorageFor(fieldName) {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        var raw = localStorage.getItem(key);
                        if (!raw) continue;
                        try {
                            var parsed = JSON.parse(raw);
                            var found = findFieldDeep(parsed, fieldName, 0);
                            if (found) return String(found);
                        } catch (e) {
                            // không phải JSON, bỏ qua
                        }
                    }
                    return '';
                }

                var plateValue = getCurrentPlateInputValue();
                if (!plateValue) {
                    reportError('Không tìm thấy ô biển số đang nhập trên trang. Bạn tra cứu biển số trên trang VOS trước rồi mới bấm Check.');
                    return;
                }

                var accessToken = getAccessToken();
                if (!accessToken) {
                    reportError('Không tìm thấy token đăng nhập (access_token) trong localStorage. Bạn đăng nhập lại trên trang VOS rồi thử Check lại.');
                    return;
                }
                var browserId = scanLocalStorageFor('browser_id');
                var browserIp = scanLocalStorageFor('browser_ip');

                try {
                    var url = '/api/360/registration-info?plate=' + encodeURIComponent(plateValue);
                    var headers = {
                        'Accept': 'application/json',
                        'Authorization': 'Bearer ' + accessToken
                    };
                    if (browserId) headers['browser_id'] = browserId;
                    if (browserIp) headers['browser_ip'] = browserIp;

                    var res = await fetch(url, {
                        method: 'GET',
                        credentials: 'include',
                        headers: headers
                    });

                    if (!res.ok) {
                        if (res.status === 403) {
                            reportError('API từ chối truy cập (403) - có thể thiếu quyền hoặc token không hợp lệ.');
                        } else if (res.status === 401) {
                            reportError('Phiên đăng nhập đã hết hạn (401) - đăng nhập lại trên VOS rồi Check lại.');
                        } else {
                            reportError('Gọi API thất bại, mã lỗi HTTP ' + res.status);
                        }
                        return;
                    }

                    var json;
                    try {
                        json = await res.json();
                    } catch (parseErr) {
                        reportError('Không đọc được dữ liệu trả về (không phải JSON hợp lệ)');
                        return;
                    }

                    // Response có thể bọc trong "data" hoặc trả phẳng ngay ở gốc - đọc cả 2 kiểu
                    var payload = (json && json.data && typeof json.data === 'object') ? json.data : json;
                    var plateRegistry = payload && payload.plate_registry ? String(payload.plate_registry) : '';
                    var frameNumber = payload && payload.frame_number ? String(payload.frame_number) : '';

                    if (!plateRegistry && !frameNumber) {
                        reportError('API trả về thành công nhưng không có dữ liệu biển số/số khung');
                        return;
                    }

                    reportSuccess(plateRegistry, frameNumber);
                } catch (networkErr) {
                    reportError('Lỗi mạng hoặc lỗi khi gọi API: ' + (networkErr && networkErr.message ? networkErr.message : String(networkErr)));
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Mở Chrome thật (vượt qua được Cloudflare); nếu máy không có Chrome, dùng trình duyệt mặc định. */
    private fun openInChromeOrDefaultBrowser(url: String) {
        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.android.chrome")
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(chromeIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Không tìm thấy trình duyệt nào trên máy để mở trang.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun customPriceImageFile(): File = File(filesDir, CUSTOM_PRICE_IMAGE_FILE)

    private fun updatePriceTableDisplay() {
        val customImage = customPriceImageFile()
        if (customImage.exists()) {
            val bitmap = decodeDisplaySizedBitmap(customImage)
            if (bitmap != null) {
                binding.priceTableScrollView.visibility = android.view.View.GONE
                binding.imgCustomPriceTable.visibility = android.view.View.VISIBLE
                binding.imgCustomPriceTable.setImageBitmap(bitmap)
                binding.btnDeletePriceImage.visibility = android.view.View.VISIBLE
            } else {
                customImage.delete()
                showDefaultPriceTable()
                Toast.makeText(this, "Ảnh bảng giá bị lỗi, đã quay về bảng mặc định.", Toast.LENGTH_LONG).show()
            }
        } else {
            showDefaultPriceTable()
        }
    }

    private fun showDefaultPriceTable() {
        binding.imgCustomPriceTable.setImageDrawable(null)
        binding.imgCustomPriceTable.visibility = android.view.View.GONE
        binding.priceTableScrollView.visibility = android.view.View.VISIBLE
        binding.btnDeletePriceImage.visibility = android.view.View.GONE
    }

    private fun decodeDisplaySizedBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PRICE_IMAGE_DISPLAY_SIZE ||
            bounds.outHeight / sampleSize > MAX_PRICE_IMAGE_DISPLAY_SIZE
        ) {
            sampleSize *= 2
        }

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
        return decoded?.rotateByExif(file)
    }

    private fun Bitmap.rotateByExif(file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return this

        return Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(degrees) },
            true
        )
    }

    private fun saveCustomPriceImage(uri: Uri) {
        try {
            val target = customPriceImageFile()
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, "Không đọc được ảnh bảng giá đã chọn.", Toast.LENGTH_LONG).show()
                return
            }

            updatePriceTableDisplay()
            Toast.makeText(this, "Đã lưu ảnh bảng giá", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không lưu được ảnh bảng giá: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteCustomPriceImage() {
        val deleted = customPriceImageFile().delete()
        updatePriceTableDisplay()
        val message = if (deleted) "Đã xoá ảnh bảng giá" else "Đang dùng bảng giá mặc định"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Cầu nối JS -> Android, nhận kết quả gọi API tra đăng kiểm. */
    private inner class RegistryBridge {
        /**
         * Nhận biển số (plate_registry) + số khung đầy đủ (frame_number) từ API thật
         * của VETC, lấy 6 ký tự cuối số khung, rồi COPY LẦN LƯỢT vào clipboard:
         * 6 số cuối số khung TRƯỚC, biển số xe SAU - đúng thứ tự bạn yêu cầu - có
         * Toast báo mỗi lần copy, sau đó mới tự mở Chrome tới trang đăng kiểm.
         */
        @JavascriptInterface
        fun onRegistryDataExtracted(plate: String, frameNumberRaw: String, error: String) {
            runOnUiThread {
                if (error.isNotBlank()) {
                    Toast.makeText(this@WebViewActivity, error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val plateClean = plate.uppercase().filter { it.isLetterOrDigit() }
                val frameClean = frameNumberRaw.uppercase().filter { it.isLetterOrDigit() }
                val frameLast6 = if (frameClean.length >= 6) frameClean.takeLast(6) else frameClean

                if (plateClean.isBlank() || frameLast6.isBlank()) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "API trả về thiếu dữ liệu biển số hoặc số khung.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                // Bước 1: copy 6 số cuối số khung trước
                copyToClipboard("Số khung", frameLast6)
                Toast.makeText(this@WebViewActivity, "Đã copy số khung: $frameLast6", Toast.LENGTH_SHORT).show()

                // Bước 2: sau đó copy biển số (ghi đè clipboard). Giữ nhịp rất ngắn
                // để clipboard/clipboard history kịp nhận 2 lần copy, rồi mở Chrome ngay.
                handler.postDelayed({
                    copyToClipboard("Biển số", plateClean)
                    Toast.makeText(this@WebViewActivity, "Đã copy biển số: $plateClean", Toast.LENGTH_SHORT).show()

                    // Bước 3: mở Chrome ngay sau khi copy xong cả 2
                    openInChromeOrDefaultBrowser(VR_REGISTER_URL)
                }, 50)
            }
        }
    }

    /** Dựng bảng giá theo loại xe từ PriceData, hiển thị trong lớp phủ mờ khi bấm nút "Giá". */
    private fun buildPriceTable() {
        val container = binding.priceTableContainer
        container.removeAllViews()

        val headerColor = android.graphics.Color.parseColor("#FFC400")
        val rowTextColor = android.graphics.Color.WHITE
        val altRowBg = android.graphics.Color.parseColor("#22FFFFFF")

        fun cell(text: String, weight: Float, isHeader: Boolean): android.widget.TextView {
            return android.widget.TextView(this).apply {
                this.text = text
                setTextColor(if (isHeader) headerColor else rowTextColor)
                textSize = if (isHeader) 13f else 13f
                if (isHeader) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                gravity = if (weight > 1.5f) android.view.Gravity.START else android.view.Gravity.END
            }
        }

        fun addRow(name: String, l1: String, l2: String, l3: String, l4: String, l5: String, isHeader: Boolean, rowIndex: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (!isHeader && rowIndex % 2 == 1) setBackgroundColor(altRowBg)
            }
            row.addView(cell(name, 2.2f, isHeader))
            row.addView(cell(l1, 1f, isHeader))
            row.addView(cell(l2, 1f, isHeader))
            row.addView(cell(l3, 1f, isHeader))
            row.addView(cell(l4, 1f, isHeader))
            row.addView(cell(l5, 1f, isHeader))
            container.addView(row)
        }

        for (section in PriceData.sections) {
            val sectionTitle = android.widget.TextView(this).apply {
                text = section.title
                setTextColor(headerColor)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(8, 24, 8, 8)
            }
            container.addView(sectionTitle)

            addRow("Trạm thu phí", "Loại 1", "Loại 2", "Loại 3", "Loại 4", "Loại 5", isHeader = true, rowIndex = 0)
            section.rows.forEachIndexed { index, row ->
                addRow(row.stationName, row.loai1, row.loai2, row.loai3, row.loai4, row.loai5, isHeader = false, rowIndex = index)
            }
        }
    }

    /** Cầu nối JS -> Android, dùng để hỏi lưu mật khẩu giống thanh nhắc của Chrome. */
    private inner class CredentialBridge {
        @JavascriptInterface
        fun onLoginSubmitted(username: String, password: String) {
            runOnUiThread {
                if (username.isBlank() || password.isBlank()) return@runOnUiThread

                val alreadySaved = CredentialStore.getUsername(this@WebViewActivity) == username &&
                    CredentialStore.getPassword(this@WebViewActivity) == password
                if (alreadySaved) return@runOnUiThread

                AlertDialog.Builder(this@WebViewActivity)
                    .setTitle("Lưu mật khẩu?")
                    .setMessage("Lưu tài khoản \"$username\" cho vos.vetc.com.vn để lần sau tự động điền?")
                    .setPositiveButton("Lưu") { _, _ ->
                        CredentialStore.save(this@WebViewActivity, username, password)
                        Toast.makeText(this@WebViewActivity, "Đã lưu mật khẩu trên máy", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Không, cảm ơn", null)
                    .show()
            }
        }
    }

    /** Dialog xem/sửa/xoá mật khẩu đã lưu - giống trình quản lý mật khẩu trong Chrome. */
    private fun showManageCredentialsDialog() {
        val savedUser = CredentialStore.getUsername(this)
        val savedPass = CredentialStore.getPassword(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val userInput = EditText(this).apply {
            hint = "Tài khoản"
            setText(savedUser ?: "")
        }
        val passInput = EditText(this).apply {
            hint = "Mật khẩu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPass ?: "")
        }
        container.addView(userInput)
        container.addView(passInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Quản lý mật khẩu đã lưu")
            .setView(container)
            .setPositiveButton("Lưu", null) // gán listener riêng bên dưới để không tự đóng khi rỗng
            .setNeutralButton("Xoá đã lưu") { _, _ ->
                CredentialStore.clear(this)
                Toast.makeText(this, "Đã xoá mật khẩu đã lưu", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = userInput.text.toString().trim()
                val p = passInput.text.toString()
                if (u.isBlank() || p.isBlank()) {
                    Toast.makeText(this, "Nhập đủ tài khoản và mật khẩu", Toast.LENGTH_SHORT).show()
                } else {
                    CredentialStore.save(this, u, p)
                    Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun escapeJs(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun runAutoFillScript(webView: WebView, plateValue: String, username: String, password: String) {
        val safePlate = escapeJs(plateValue)
        val safeUser = escapeJs(username)
        val safePass = escapeJs(password)

        val js = """
            (function() {
                var PLATE_VALUE = '$safePlate';
                var VOS_USERNAME = '$safeUser';
                var VOS_PASSWORD = '$safePass';
                var attempts = 0;
                var maxAttempts = 20;
                var loginAttemptedOnce = false;
                var listenerAttached = false;

                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    return style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
                }

                function bodyMentions(keywords) {
                    var text = (document.body.innerText || '').toLowerCase();
                    for (var i = 0; i < keywords.length; i++) {
                        if (text.indexOf(keywords[i]) !== -1) return true;
                    }
                    return false;
                }

                function getVisiblePasswordField() {
                    var pw = document.querySelector('input[type=password]');
                    return isVisible(pw) ? pw : null;
                }

                function isOtpScreen() {
                    return bodyMentions(['otp', 'xac thuc', 'xác thực', 'mã xác nhận', 'ma xac nhan']);
                }

                function setValue(el, val) {
                    el.focus();
                    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (nativeSetter && nativeSetter.set) {
                        nativeSetter.set.call(el, val);
                    } else {
                        el.value = val;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                function findUsernameField(passwordField) {
                    var candidates = Array.prototype.slice.call(document.querySelectorAll('input'));
                    var keywords = ['user', 'username', 'account', 'tai_khoan', 'taikhoan', 'login', 'email'];
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        if (el === passwordField || !isVisible(el)) continue;
                        var typeOk = !el.type || ['text','email',''].indexOf(el.type) !== -1;
                        if (!typeOk) continue;
                        var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '')).toLowerCase();
                        for (var k = 0; k < keywords.length; k++) {
                            if (hint.indexOf(keywords[k]) !== -1) return el;
                        }
                    }
                    for (var j = 0; j < candidates.length; j++) {
                        var el2 = candidates[j];
                        if (el2 === passwordField || !isVisible(el2)) continue;
                        var typeOk2 = !el2.type || ['text','email',''].indexOf(el2.type) !== -1;
                        if (typeOk2) return el2;
                    }
                    return null;
                }

                function tryClickLoginButton() {
                    var buttons = Array.prototype.slice.call(document.querySelectorAll('button, input[type=submit]'));
                    var keywords = ['đăng nhập', 'dang nhap', 'login', 'sign in'];
                    for (var i = 0; i < buttons.length; i++) {
                        var btn = buttons[i];
                        if (!isVisible(btn)) continue;
                        var label = (btn.innerText || btn.value || '').toLowerCase();
                        for (var k = 0; k < keywords.length; k++) {
                            if (label.indexOf(keywords[k]) !== -1) {
                                btn.click();
                                return true;
                            }
                        }
                    }
                    return false;
                }

                // Gắn lắng nghe để khi NGƯỜI DÙNG tự tay bấm nút đăng nhập (không phải app tự điền),
                // báo giá trị tài khoản/mật khẩu về Android để hỏi lưu, giống thanh nhắc của Chrome.
                function attachManualSubmitListener(passwordField) {
                    if (listenerAttached) return;
                    var form = passwordField.closest ? passwordField.closest('form') : null;
                    var userField = findUsernameField(passwordField);

                    function reportIfFilled() {
                        var u = userField ? userField.value : '';
                        var p = passwordField.value;
                        if (u && p && window.AndroidCredentialBridge) {
                            window.AndroidCredentialBridge.onLoginSubmitted(u, p);
                        }
                    }

                    if (form) {
                        form.addEventListener('submit', reportIfFilled, true);
                    }
                    document.addEventListener('click', function(e) {
                        var t = e.target;
                        if (!t) return;
                        var label = ((t.innerText || t.value || '') + '').toLowerCase();
                        if (label.indexOf('đăng nhập') !== -1 || label.indexOf('dang nhap') !== -1 ||
                            label.indexOf('login') !== -1 || label.indexOf('sign in') !== -1) {
                            reportIfFilled();
                        }
                    }, true);

                    listenerAttached = true;
                }

                function findPlateField() {
                    var candidates = Array.prototype.slice.call(document.querySelectorAll('input, textarea'));
                    var visible = candidates.filter(function(el) {
                        var typeOk = !el.type || ['text','search','tel','number',''].indexOf(el.type) !== -1;
                        return typeOk && isVisible(el);
                    });
                    if (visible.length === 0) return null;

                    var keywords = ['bien', 'bienso', 'plate', 'xe', 'search', 'key', 'sochu', 'so_xe', 'soxe'];
                    for (var i = 0; i < visible.length; i++) {
                        var el = visible[i];
                        var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '')).toLowerCase();
                        for (var k = 0; k < keywords.length; k++) {
                            if (hint.indexOf(keywords[k]) !== -1) return el;
                        }
                    }
                    return visible[0];
                }

                function tick() {
                    attempts++;

                    var passwordField = getVisiblePasswordField();

                    if (passwordField) {
                        attachManualSubmitListener(passwordField);

                        if (isOtpScreen()) {
                            if (attempts < maxAttempts) setTimeout(tick, 500);
                            return;
                        }
                        if (!loginAttemptedOnce && VOS_USERNAME && VOS_PASSWORD) {
                            var userField = findUsernameField(passwordField);
                            if (userField) setValue(userField, VOS_USERNAME);
                            setValue(passwordField, VOS_PASSWORD);
                            loginAttemptedOnce = true;
                            setTimeout(function() { tryClickLoginButton(); }, 300);
                        }
                        if (attempts < maxAttempts) setTimeout(tick, 500);
                        return;
                    }

                    if (isOtpScreen()) {
                        if (attempts < maxAttempts) setTimeout(tick, 500);
                        return;
                    }

                    if (!PLATE_VALUE) {
                        return; // bấm "Duyệt" thẳng, chưa có biển số để điền
                    }

                    var plateField = findPlateField();
                    if (plateField) {
                        setValue(plateField, PLATE_VALUE);
                        return;
                    }
                    if (attempts < maxAttempts) setTimeout(tick, 500);
                }

                tick();
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    companion object {
        const val EXTRA_PLATE_CODE = "extra_plate_code"
        const val EXTRA_PLATE_IMAGE_PATH = "extra_plate_image_path"
        const val EXTRA_TARGET_URL = "extra_target_url"
        private const val TARGET_URL = "https://vos.vetc.com.vn/"
        private const val VR_REGISTER_URL = "https://gcndangkiem.vr.org.vn/"
        private const val CUSTOM_PRICE_IMAGE_FILE = "custom_price_table.jpg"
        private const val MAX_PRICE_IMAGE_DISPLAY_SIZE = 2200
    }
}
