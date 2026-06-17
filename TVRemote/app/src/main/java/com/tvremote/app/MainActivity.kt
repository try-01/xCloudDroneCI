package com.tvremote.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var tvController: TvController
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while remote is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvController = TvController(applicationContext)
        tvController.loadConfig()

        webView = WebView(this).apply {
            setContentView(this)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString.replace("; wv", "")

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // If already configured, connect automatically
                    if (tvController.tvIp.isNotEmpty()) {
                        tvController.connect()
                    }
                }
            }

            addJavascriptInterface(JavascriptBridge(), "Android")
        }

        webView.loadUrl("file:///android_asset/index.html")

        // Status callback: notify JS when connection status changes
        tvController.setStatusCallback { connected ->
            scope.launch {
                val js = "if(typeof onNativeStatus==='function') onNativeStatus($connected);"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun notifyJs(js: String) {
        scope.launch {
            webView.evaluateJavascript(js, null)
        }
    }

    // --- JavascriptInterface Bridge ---
    inner class JavascriptBridge {

        @JavascriptInterface
        fun sendKey(key: String): Boolean {
            return tvController.sendKey(key)
        }

        @JavascriptInterface
        fun pressKey(key: String): Boolean {
            return tvController.pressKey(key)
        }

        @JavascriptInterface
        fun holdKey(key: String) {
            tvController.holdKey(key)
        }

        @JavascriptInterface
        fun releaseKey(key: String): Boolean {
            return tvController.releaseKey(key)
        }

        @JavascriptInterface
        fun sendText(text: String): Boolean {
            return tvController.sendText(text)
        }

        @JavascriptInterface
        fun launchApp(appId: String): Boolean {
            return tvController.launchApp(appId)
        }

        @JavascriptInterface
        fun isConnected(): Boolean {
            return tvController.connected
        }

        @JavascriptInterface
        fun getPowerState(): String {
            return tvController.getPowerState()
        }

        @JavascriptInterface
        fun sendWoL() {
            tvController.sendWoL()
        }

        @JavascriptInterface
        fun getTvIp(): String {
            return tvController.tvIp
        }

        @JavascriptInterface
        fun hasToken(): Boolean {
            return tvController.token.isNotEmpty()
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun reconnect() {
            tvController.connect()
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "JS: $message")
        }

        // --- Setup flow methods ---

        @JavascriptInterface
        fun isConfigured(): Boolean {
            return tvController.tvIp.isNotEmpty()
        }

        @JavascriptInterface
        fun startScan(): String {
            // Run scan synchronously (called from background thread by JS bridge)
            val result = runBlocking {
                NetworkScanner.scan(applicationContext)
            }
            return if (result != null) "${result.ip}|${result.mac}" else ""
        }

        @JavascriptInterface
        fun saveConfig(ip: String, mac: String) {
            tvController.saveConfig(ip, mac)
        }

        @JavascriptInterface
        fun connectToTv(): Boolean {
            tvController.connect()
            return true
        }

        @JavascriptInterface
        fun forceReconnect() {
            tvController.destroy()
            tvController.loadConfig()
            tvController.connect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tvController.destroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
