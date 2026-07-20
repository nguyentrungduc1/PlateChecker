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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingPlateCode = intent.getStringExtra(EXTRA_PLATE_CODE) ?: ""

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

                runAutoFillScript(webView, pendingPlateCode, savedUser ?: "", savedPass ?: "")
            }
        }

        webView.loadUrl(TARGET_URL)

        binding.btnManageCredentials.setOnClickListener { showManageCredentialsDialog() }
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
        private const val TARGET_URL = "https://vos.vetc.com.vn/"
    }
}
