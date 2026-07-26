package com.leeduc.platechecker

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import androidx.appcompat.app.AppCompatActivity
import com.leeduc.platechecker.databinding.ActivityWebviewBinding

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
    private var pendingRegistryPlate: String = ""
    private var pendingRegistryChassis: String = ""

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
        webView.settings.userAgentString = webView.settings.userAgentString.replace("; wv", "")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Cầu nối để trang web báo về Android khi người dùng tự tay bấm nút đăng nhập
        webView.addJavascriptInterface(CredentialBridge(), "AndroidCredentialBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()

                val savedUser = CredentialStore.getUsername(this@WebViewActivity)
                val savedPass = CredentialStore.getPassword(this@WebViewActivity)

                if (url?.contains("vos.vetc.com.vn") == true) {
                    runAutoFillScript(webView, pendingPlateCode, savedUser ?: "", savedPass ?: "")
                }

                if (url?.contains("gcndangkiem.vr.org.vn") == true && pendingRegistryPlate.isNotBlank()) {
                    runFillRegistryFieldsScript(webView, pendingRegistryPlate, pendingRegistryChassis)
                    pendingRegistryPlate = ""
                    pendingRegistryChassis = ""
                }
            }
        }

        webView.loadUrl(targetUrl)

        binding.btnRegistryCheck.setOnClickListener { openRegistrySite() }
        binding.btnManageCredentials.setOnClickListener { showManageCredentialsDialog() }

        buildPriceTable()
        binding.btnPriceList.setOnClickListener { togglePriceOverlay(show = true) }
        binding.btnClosePriceList.setOnClickListener { togglePriceOverlay(show = false) }
        binding.priceOverlay.setOnClickListener { togglePriceOverlay(show = false) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.priceOverlay.visibility == android.view.View.VISIBLE) {
                    togglePriceOverlay(show = false)
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** Bật/tắt lớp phủ bảng giá, đồng thời ẩn/hiện 2 nút nổi (Giá, Quản lý mật khẩu) cho gọn màn hình. */
    private fun togglePriceOverlay(show: Boolean) {
        binding.priceOverlay.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        val buttonsVisibility = if (show) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnRegistryCheck.visibility = buttonsVisibility
        binding.btnPriceList.visibility = buttonsVisibility
        binding.btnManageCredentials.visibility = buttonsVisibility
    }

    private fun openRegistrySite() {
        Toast.makeText(this, "Đang gọi API lấy biển số + số khung...", Toast.LENGTH_SHORT).show()
        extractRegistryDataThenNavigate(binding.webView)
    }

    /**
     * [Hướng B - gọi thẳng API] Thay vì "đoán" biển số/số khung từ cấu trúc HTML hiển thị,
     * hàm này chạy `fetch()` ngay trong WebView để gọi thẳng API nội bộ của VOS:
     *   GET /api/360/registration-info?plate=<biển số>
     * Vì fetch chạy cùng origin (vos.vetc.com.vn) và cùng tiến trình với trang đang mở,
     * nó tự động dùng chung cookie/phiên đăng nhập hiện có (thêm `credentials: 'include'`
     * để chắc chắn cookie luôn được gửi kèm) - không cần đăng nhập lại, không cần API key riêng.
     *
     * Kết quả trả về là JSON thật của hệ thống, lấy đúng 2 trường:
     *   - plate_registry: biển số đăng ký (vd "73A-196.23T")
     *   - frame_number:   số khung đầy đủ (vd "RNYBB41A6LC209211")
     * nên không còn phụ thuộc vào cách trang hiển thị (label, DOM, panel ẩn/hiện...).
     */
    private fun extractRegistryDataThenNavigate(webView: WebView) {
        val js = """
            (function() {
                function norm(s) { return (s || '').toLowerCase(); }

                function reportError(message) {
                    if (window.AndroidCredentialBridge) {
                        window.AndroidCredentialBridge.onRegistryDataError(message);
                    }
                }

                // DOM chỉ còn dùng để LẤY GIÁ TRỊ Ô TÌM KIẾM (biển số vừa tra cứu);
                // "số khung" không còn đoán qua DOM nữa - lấy thẳng từ JSON của API.
                function getPlateInputValue() {
                    var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));
                    for (var i = 0; i < inputs.length; i++) {
                        var el = inputs[i];
                        var hint = norm(el.name) + ' ' + norm(el.id) + ' ' + norm(el.placeholder);
                        if ((hint.indexOf('bien') !== -1 || hint.indexOf('etag') !== -1) && el.value) {
                            return el.value;
                        }
                    }
                    return '';
                }

                var rawPlate = getPlateInputValue();
                if (!rawPlate) {
                    reportError('Không tìm thấy ô biển số trên trang để lấy giá trị tra cứu.');
                    return;
                }

                // Chuẩn hoá về đúng dạng API cần (giống request thật:
                // .../api/360/registration-info?plate=73A19623T): chỉ chữ + số, viết hoa,
                // bỏ hết dấu gạch ngang, dấu chấm, khoảng trắng.
                var plateForApi = rawPlate.toUpperCase().replace(/[^A-Z0-9]/g, '');

                fetch('/api/360/registration-info?plate=' + encodeURIComponent(plateForApi), {
                    method: 'GET',
                    credentials: 'include',
                    headers: { 'Accept': 'application/json' }
                })
                .then(function(res) {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.json();
                })
                .then(function(data) {
                    var plateRegistry = (data && data.plate_registry) ? String(data.plate_registry) : '';
                    var frameNumber = (data && data.frame_number) ? String(data.frame_number) : '';

                    if (!plateRegistry || !frameNumber) {
                        reportError('API trả về thiếu biển số hoặc số khung cho biển "' + plateForApi + '".');
                        return;
                    }

                    if (window.AndroidCredentialBridge) {
                        window.AndroidCredentialBridge.onRegistryDataExtracted(plateRegistry, frameNumber);
                    }
                })
                .catch(function(err) {
                    reportError('Gọi API tra cứu thất bại: ' + (err && err.message ? err.message : err));
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /** Điền biển số + 6 số cuối số khung vào 2 ô nhập liệu đầu tiên của trang đăng kiểm. */
    private fun runFillRegistryFieldsScript(webView: WebView, plate: String, chassisLast6: String) {
        val safePlate = escapeJs(plate)
        val safeChassis = escapeJs(chassisLast6)

        val js = """
            (function() {
                var PLATE_VALUE = '$safePlate';
                var CHASSIS_VALUE = '$safeChassis';
                var attempts = 0;
                var maxAttempts = 20;

                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    return style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
                }

                function setValue(el, val) {
                    el.focus();
                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    if (nativeSetter && nativeSetter.set) {
                        nativeSetter.set.call(el, val);
                    } else {
                        el.value = val;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                function tick() {
                    attempts++;

                    // Dùng đúng ID thật của trang gcndangkiem.vr.org.vn
                    var plateField = document.getElementById('Biendangky');
                    var chassisField = document.getElementById('SoKhung');

                    if (plateField && chassisField && isVisible(plateField) && isVisible(chassisField)) {
                        setValue(plateField, PLATE_VALUE);
                        setValue(chassisField, CHASSIS_VALUE);
                        return;
                    }

                    // Dự phòng: nếu vì lý do gì đó không thấy đúng 2 id trên (trang đổi cấu trúc),
                    // thử lấy tạm ô nhập liệu hiển thị đầu tiên và thứ hai.
                    if (attempts >= maxAttempts) {
                        var inputs = Array.prototype.slice.call(document.querySelectorAll('input, textarea'));
                        var visible = inputs.filter(function(el) {
                            var typeOk = !el.type || ['text', 'search', 'tel', 'number', ''].indexOf(el.type) !== -1;
                            return typeOk && isVisible(el);
                        });
                        if (visible.length >= 2) {
                            setValue(visible[0], PLATE_VALUE);
                            setValue(visible[1], CHASSIS_VALUE);
                        }
                        return;
                    }

                    setTimeout(tick, 500);
                }

                tick();
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
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

        /** Cầu nối JS -> Android khi gọi fetch() API tra cứu thất bại (lỗi mạng, HTTP lỗi, thiếu trường...). */
        @JavascriptInterface
        fun onRegistryDataError(message: String) {
            runOnUiThread {
                Toast.makeText(this@WebViewActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        /**
         * [Hướng B - gọi thẳng API] Nhận đúng 2 trường JSON (plate_registry, frame_number)
         * lấy được từ fetch() gọi /api/360/registration-info trên trang VOS, lấy 6 số cuối
         * của số khung, rồi điều hướng WebView thẳng sang trang đăng kiểm (không nhúng
         * iframe) để tự điền 2 ô đầu tiên bên đó.
         */
        @JavascriptInterface
        fun onRegistryDataExtracted(plate: String, chassisRaw: String) {
            runOnUiThread {
                val chassisDigitsOnly = chassisRaw.filter { it.isLetterOrDigit() }
                val chassisLast6 = if (chassisDigitsOnly.length >= 6) {
                    chassisDigitsOnly.takeLast(6)
                } else {
                    chassisDigitsOnly
                }

                if (plate.isBlank() || chassisLast6.isBlank()) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "API tra cứu không trả về đủ biển số/số khung cho xe này.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                pendingRegistryPlate = plate
                pendingRegistryChassis = chassisLast6
                binding.webView.loadUrl(VR_REGISTER_URL)
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
    }
}
