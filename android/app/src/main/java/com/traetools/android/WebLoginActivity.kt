package com.traetools.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.traetools.android.databinding.ActivityWebLoginBinding
import com.traetools.android.secret.JwtUtils

/**
 * 内嵌 WebView 登录 trae.cn，捕获 HttpOnly 会话 Cookie `X-Cloudide-Session`（约 14 天有效期）。
 * 该 Cookie 是换取 JWT 与执行签到的长效凭证，登录成功后回传给 [MainActivity]。
 */
class WebLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebLoginBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (finished) return
            tryCapture()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView
        @Suppress("SetJavaScriptEnabled")
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = DESKTOP_UA
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = WebViewClient()

        // 先清空 Cookie，确保读到的是用户本次登录产生的新会话，而不是上一次的残留凭证
        CookieManager.getInstance().removeAllCookies {
            handler.post {
                webView.loadUrl(HOME_URL)
                handler.postDelayed(pollTask, POLL_INTERVAL_MS)
            }
        }
    }

    /** 轮询读取页面 token 与 Cookie；两者齐备即视为登录成功。 */
    private fun tryCapture() {
        if (finished) return
        binding.webView.evaluateJavascript(READ_TOKEN_JS) { raw ->
            val jwt = raw?.trim('"').orEmpty()
            if (jwt.length < 40) return@evaluateJavascript
            val session = readSessionCookie()
            if (session.isNullOrEmpty()) return@evaluateJavascript
            finishWithResult(session, jwt)
        }
    }

    private fun readSessionCookie(): String? {
        val cookies = CookieManager.getInstance().getCookie(TRAE_ORIGIN) ?: return null
        for (part in cookies.split(";")) {
            val kv = part.trim()
            if (kv.startsWith(SESSION_COOKIE_PREFIX)) {
                return kv.substring(SESSION_COOKIE_PREFIX.length)
            }
        }
        return null
    }

    private fun finishWithResult(session: String, jwt: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        binding.tvStatus.setText(R.string.web_login_success)
        val intent = Intent()
            .putExtra(RESULT_SESSION, session)
            .putExtra(RESULT_UID, JwtUtils.parseUid(jwt))
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val RESULT_SESSION = "session"
        const val RESULT_UID = "uid"

        private const val HOME_URL = "https://www.trae.cn/"
        private const val TRAE_ORIGIN = "https://www.trae.cn"
        private const val SESSION_COOKIE_PREFIX = "X-Cloudide-Session="
        private const val POLL_INTERVAL_MS = 1500L

        /** 使用桌面版 UA，trae.cn 的登录页与 localStorage 键位以 PC 端为准。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val READ_TOKEN_JS =
            "(function(){try{return localStorage.getItem('Cloud-IDE-Token')||'';}catch(e){return '';}})()"
    }
}