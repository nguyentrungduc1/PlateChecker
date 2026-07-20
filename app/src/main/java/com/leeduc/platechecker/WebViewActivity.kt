package com.leeduc.platechecker

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.leeduc.platechecker.databinding.ActivityWebviewBinding

/**
 * WebView đóng vai trò như một "Chrome thu nhỏ" bên trong app:
 * - Dùng chung engine Chromium như Chrome ngoài, nên đăng nhập, mật khẩu, OTP,
 *   phiên làm việc (session cookie) đều hoạt động y hệt trình duyệt thật.
 * - Cookie được bật lưu vĩnh viễn (persistent) nên sau khi đăng nhập + xác thực OTP,
 *   phiên đăng nhập vẫn còn nguyên trong ~7 tiếng như trang web quy định, kể cả khi
 *   tắt mở lại app - không cần đăng nhập lại cho tới khi phiên hết hạn.
 * - Ô "biển số" chỉ được tự động điền khi KHÔNG đang ở màn hình đăng nhập/OTP,
 *   để tránh vô tình ghi đè vào ô tài khoản/OTP.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val plateCode = intent.getStringExtra(EXTRA_PLATE_CODE) ?: ""

        val webView: WebView = binding.webView

        // --- Cấu hình để WebView cư xử giống Chrome thật ---
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        // User-Agent giả lập trình duyệt di động thường, tránh site trả về bản giao diện khác cho WebView
        webView.settings.userAgentString = webView.settings.userAgentString
            .replace("; wv", "") // bỏ cờ "wv" để site không nhận diện là WebView nhúng

        // --- Bật lưu cookie vĩnh viễn để giữ phiên đăng nhập/OTP qua các lần mở app ---
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush() // đảm bảo cookie phiên đăng nhập được ghi xuống đĩa
                tryAutoFillPlate(webView, plateCode)
            }
        }

        webView.loadUrl(TARGET_URL)
    }

    /**
     * Thử điền biển số vào ô nhập liệu, có cơ chế:
     * - Bỏ qua nếu đang ở màn hình đăng nhập / nhập mật khẩu / OTP.
     * - Ưu tiên ô có tên/placeholder gợi ý liên quan biển số (bien, plate, xe, search...).
     * - Nếu trang tải nội dung kiểu SPA (chậm hơn sự kiện onPageFinished), sẽ thử lại
     *   nhiều lần trong vài giây thay vì chỉ chạy đúng 1 lần.
     */
    private fun tryAutoFillPlate(webView: WebView, value: String) {
        val safeValue = value.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                var PLATE_VALUE = '$safeValue';
                var attempts = 0;
                var maxAttempts = 20; // ~10 giây, thử mỗi 500ms

                function isLoginOrOtpScreen() {
                    var hasPasswordField = document.querySelector('input[type=password]') !== null
                        && isVisible(document.querySelector('input[type=password]'));
                    var bodyText = (document.body.innerText || '').toLowerCase();
                    var mentionsOtp = bodyText.indexOf('otp') !== -1 || bodyText.indexOf('xac thuc') !== -1
                        || bodyText.indexOf('xác thực') !== -1 || bodyText.indexOf('mã xác nhận') !== -1;
                    return hasPasswordField || mentionsOtp;
                }

                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    return style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
                }

                function findTargetInput() {
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
                    // Không tìm thấy gợi ý tên field -> lấy ô hiển thị đầu tiên
                    return visible[0];
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

                function tick() {
                    attempts++;
                    if (isLoginOrOtpScreen()) {
                        // Đang ở màn đăng nhập/OTP -> không điền, để người dùng tự xử lý,
                        // nhưng vẫn thử lại sau vì có thể đang chuyển trang sau khi đăng nhập xong.
                        if (attempts < maxAttempts) setTimeout(tick, 500);
                        return;
                    }
                    var target = findTargetInput();
                    if (target) {
                        setValue(target, PLATE_VALUE);
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
