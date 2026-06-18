package com.tvremote.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var tvController: TvController
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wasConnected = false
    private var showingDisconnectDialog = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Keep screen on while remote is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvController = TvController(applicationContext)
        tvController.loadConfig()

        // Create a FrameLayout wrapper to handle insets
        val rootLayout = FrameLayout(this)

        webView = WebView(this).apply {
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
                        tvController.serverOn()
                    }
                }
            }

            addJavascriptInterface(JavascriptBridge(), "Android")
        }

        // Add WebView to the wrapper layout
        rootLayout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Set the wrapper as content view
        setContentView(rootLayout)

        // Handle Window Insets for status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding for status bar (top) and navigation bar (bottom)
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )

            // Also inject the status bar height into JavaScript for the HTML UI
            val statusBarHeight = insets.top
            val navBarHeight = insets.bottom
            val js = """
                (function() {
                    document.documentElement.style.setProperty('--status-bar-height', '${statusBarHeight}px');
                    document.documentElement.style.setProperty('--nav-bar-height', '${navBarHeight}px');
                    document.documentElement.style.paddingTop = '${statusBarHeight}px';
                    document.body.style.paddingTop = '0px';
                    document.body.style.height = 'calc(100dvh - ${statusBarHeight}px - ${navBarHeight}px)';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)

            WindowInsetsCompat.CONSUMED
        }

        webView.loadUrl("file:///android_asset/index.html")

        // Status callback: notify JS when connection status changes
        tvController.setStatusCallback { connected ->
            scope.launch {
                val js = "if(typeof onNativeStatus==='function') onNativeStatus($connected);"
                webView.evaluateJavascript(js, null)

                // Detect sudden disconnection while on remote page
                if (wasConnected && !connected && tvController.isServerActive) {
                    onConnectionLost()
                }
                wasConnected = connected
            }
        }
    }

    /**
     * Called when connection is lost unexpectedly while on remote page.
     * Shows dialog with Reconnect / Reset options.
     */
    private fun onConnectionLost() {
        if (showingDisconnectDialog) return
        if (!tvController.isServerActive) return

        showingDisconnectDialog = true
        Log.d(TAG, "Connection lost, showing disconnect dialog")

        scope.launch {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Koneksi Terputus")
                .setMessage("Koneksi ke TV terputus. Apa yang ingin Anda lakukan?")
                .setCancelable(false)
                .setPositiveButton("Sambungkan Ulang") { dialog, _ ->
                    dialog.dismiss()
                    showingDisconnectDialog = false
                    tvController.serverOn()
                }
                .setNegativeButton("Atur Ulang Konfigurasi") { dialog, _ ->
                    dialog.dismiss()
                    showingDisconnectDialog = false
                    resetAndGoToSetup()
                }
                .setOnCancelListener {
                    showingDisconnectDialog = false
                }
                .show()
        }
    }

    /**
     * Reset all config and redirect to setup screen.
     */
    private fun resetAndGoToSetup() {
        tvController.resetAndDisconnect()
        // Reload WebView to show setup screen
        webView.loadUrl("file:///android_asset/index.html")
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
            tvController.serverOn()
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
            tvController.serverOn()
            return true
        }

        @JavascriptInterface
        fun forceReconnect() {
            tvController.disconnect()
            tvController.loadConfig()
            tvController.serverOn()
        }

        // --- Power control ---

        @JavascriptInterface
        fun disconnectTv() {
            tvController.disconnect()
        }

        @JavascriptInterface
        fun toggleConnection(): Boolean {
            return tvController.toggleConnection()
        }

        @JavascriptInterface
        fun getTvInfo(): String {
            return tvController.getTvInfo()
        }

        @JavascriptInterface
        fun exitApp() {
            Log.d(TAG, "Exit requested from JS")
            tvController.disconnect()
            scope.cancel()
            runOnUiThread {
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        @JavascriptInterface
        fun resetConfig() {
            Log.d(TAG, "Reset config requested from JS")
            tvController.resetAndDisconnect()
            NetworkScanner.cancelScan()
            // Redirect to setup screen
            runOnUiThread {
                webView.loadUrl("file:///android_asset/index.html")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvController.disconnect()
        scope.cancel()
        webView.destroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Show exit confirmation instead of just finishing
            scope.launch {
                val js = "document.getElementById('exitModal').classList.add('show');"
                webView.evaluateJavascript(js, null)
            }
        }
    }
}
