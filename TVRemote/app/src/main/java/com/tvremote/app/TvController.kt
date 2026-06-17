package com.tvremote.app

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TvController(private val context: Context) {

    companion object {
        private const val TAG = "TvController"
        private const val TV_PORT = 8002
        private const val APP_NAME = "AndroidRemote"
        private const val PREFS_NAME = "tv_remote_prefs"
        private const val KEY_TOKEN = "tv_token"
        private const val KEY_TV_IP = "tv_ip"
        private const val KEY_TV_MAC = "tv_mac"
        private const val HOLD_SAFETY_MS = 5000L
        private const val MAX_RECONNECT_MS = 60000L
        private const val KEEPALIVE_INTERVAL_MS = 5000L
        private const val KEEPALIVE_MAX_MISSES = 2
        private const val TEXT_ENTER_DELAY_MS = 750L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scheduler = Executors.newScheduledThreadPool(4)
    private val heldTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @Volatile var tvIp: String = ""
    @Volatile var tvMac: String = ""
    @Volatile var connected = false
    @Volatile var connecting = false
    @Volatile var encodedName: String = ""

    private var wsClient: WebSocketClient? = null
    private var reconnectDelay = 5000L
    private var keepAliveMisses = 0
    private var keepAliveFuture: ScheduledFuture<*>? = null
    private var statusCallback: ((Boolean) -> Unit)? = null

    val token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""

    fun setStatusCallback(cb: (Boolean) -> Unit) {
        statusCallback = cb
    }

    fun loadConfig() {
        tvIp = prefs.getString(KEY_TV_IP, "") ?: ""
        tvMac = prefs.getString(KEY_TV_MAC, "") ?: ""
        encodedName = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)
        Log.d(TAG, "Config loaded: IP=$tvIp, MAC=$tvMac, hasToken=${token.isNotEmpty()}")
    }

    fun saveConfig(ip: String, mac: String) {
        tvIp = ip
        tvMac = mac
        prefs.edit()
            .putString(KEY_TV_IP, ip)
            .putString(KEY_TV_MAC, mac)
            .apply()
    }

    fun saveToken(newToken: String) {
        if (newToken.isNotEmpty() && newToken != token) {
            prefs.edit().putString(KEY_TOKEN, newToken).apply()
            Log.d(TAG, "Token saved: $newToken")
        }
    }

    // --- WebSocket Connection ---

    fun connect() {
        if (connected || connecting) return
        if (tvIp.isEmpty()) {
            Log.w(TAG, "No TV IP configured")
            return
        }

        connecting = true
        val url = buildUrl()
        Log.d(TAG, "Connecting to: $url")

        try {
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }), SecureRandom())

            val client = object : WebSocketClient(URI(url)) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "WebSocket opened")
                    startReadLoop()
                }

                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    onDisconnected()
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error: ${ex?.message}")
                }
            }

            wsClient = client
            client.setSocketFactory(sslCtx.socketFactory)
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            connecting = false
            scheduleReconnect()
        }
    }

    private fun startReadLoop() {
        // Read loop is handled by WebSocketClient's onMessage callback
    }

    private fun onDisconnected() {
        val wasConnected = connected
        connected = false
        connecting = false
        wsClient = null
        if (wasConnected) {
            statusCallback?.invoke(false)
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)
        Log.d(TAG, "Reconnecting in ${delay}ms")
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun buildUrl(): String {
        val t = token
        val base = "wss://$tvIp:$TV_PORT/api/v2/channels/samsung.remote.control?name=$encodedName"
        return if (t.isNotEmpty()) "$base&token=$t" else base
    }

    // --- Message Handling ---

    private fun handleMessage(raw: String) {
        try {
            val msg = JSONObject(raw)
            val event = msg.optString("event", "")
            val data = msg.optJSONObject("data")

            // Handle token (Issue B fix: save to SharedPreferences)
            if (data != null && data.has("token")) {
                val newToken = data.getString("token")
                if (newToken.isNotEmpty()) {
                    saveToken(newToken)
                }
            }

            if (event == "ms.channel.connect") {
                connected = true
                connecting = false
                reconnectDelay = 5000L
                Log.d(TAG, "TV authenticated")
                statusCallback?.invoke(true)
                startKeepAlive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message parse error: ${e.message}")
        }
    }

    // --- Keep-Alive ---

    private fun startKeepAlive() {
        keepAliveFuture?.cancel(false)
        keepAliveMisses = 0
        keepAliveFuture = scheduler.scheduleAtFixedRate({
            if (!connected) return@scheduleAtFixedRate
            if (isTvReachable()) {
                keepAliveMisses = 0
            } else {
                keepAliveMisses++
                if (keepAliveMisses >= KEEPALIVE_MAX_MISSES) {
                    Log.d(TAG, "Keep-alive failed, disconnecting")
                    keepAliveMisses = 0
                    wsClient?.close()
                }
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun isTvReachable(): Boolean {
        return try {
            val url = URL("http://$tvIp:8001/api/v2/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    // --- WebSocket Send ---

    private fun wsSend(json: JSONObject): Boolean {
        val c = wsClient
        if (c == null || !connected) {
            connect()
            return false
        }
        return try {
            c.send(json.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "wsSend failed: ${e.message}")
            false
        }
    }

    // --- TV Commands ---

    fun sendKey(key: String): Boolean {
        return sendKeyCmd(key, "Click")
    }

    private fun sendKeyCmd(key: String, cmd: String): Boolean {
        val params = JSONObject().apply {
            put("Cmd", cmd)
            put("DataOfCmd", key)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        }
        val msg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", params)
        }
        return wsSend(msg)
    }

    fun pressKey(key: String): Boolean {
        if (!sendKeyCmd(key, "Press")) return false
        armRelease(key)
        return true
    }

    fun holdKey(key: String) {
        if (heldTimers.containsKey(key)) {
            armRelease(key)
        }
    }

    fun releaseKey(key: String): Boolean {
        heldTimers.remove(key)?.cancel(false)
        return sendKeyCmd(key, "Release")
    }

    private fun armRelease(key: String) {
        heldTimers[key]?.cancel(false)
        heldTimers[key] = scheduler.schedule({
            heldTimers.remove(key)
            sendKeyCmd(key, "Release")
            Log.d(TAG, "Auto-release: $key")
        }, HOLD_SAFETY_MS, TimeUnit.MILLISECONDS)
    }

    // --- Text Input (Issue A fix: full 4-step IME sequence) ---

    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false

        // Step 1: Broadcast text received event (focuses input context on TV)
        val emitMsg = JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "custom.remote.textReceived")
                put("to", "host")
                put("data", JSONObject().apply {
                    put("text", text)
                })
            })
        }
        wsSend(emitMsg)

        // Step 2: Send Base64-encoded text via SendInputString
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val inputMsg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", encoded)
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            })
        }
        val step2 = wsSend(inputMsg)

        // Step 3: Send input end signal
        val endMsg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "SendInputEnd")
                put("DataOfCmd", "")
                put("TypeOfRemote", "SendInputEnd")
            })
        }
        wsSend(endMsg)

        // Step 4: Auto KEY_ENTER after configurable delay
        scheduler.schedule({
            sendKey("KEY_ENTER")
            Log.d(TAG, "Auto ENTER sent after text input")
        }, TEXT_ENTER_DELAY_MS, TimeUnit.MILLISECONDS)

        return step2
    }

    // --- App Launch ---

    fun launchApp(appId: String): Boolean {
        val msg = JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.apps.launch")
                put("to", "host")
                put("data", JSONObject().apply {
                    put("appId", appId)
                    put("action_type", "DEEP_LINK")
                })
            })
        }
        return wsSend(msg)
    }

    // --- Power State ---

    fun getPowerState(): String {
        return try {
            val url = URL("http://$tvIp:8001/api/v2/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) return "off"

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val device = json.optJSONObject("device") ?: return "off"

            val ps = device.optString("PowerState", "")
            if (ps == "on") return "on"
            if (ps == "off") return "off"
            if (device.has("id") && device.getString("id").isNotEmpty()) return "on"
            "off"
        } catch (_: Exception) {
            "off"
        }
    }

    // --- WoL ---

    fun sendWoL() {
        if (tvMac.isEmpty()) return
        WakeOnLan.send(tvMac, tvIp)
        // Schedule reconnection attempts after WoL
        reconnectDelay = 5000L
        for (delay in listOf(5L, 15L, 25L, 40L)) {
            scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
        }
    }

    // --- Cleanup ---

    fun destroy() {
        keepAliveFuture?.cancel(false)
        heldTimers.values.forEach { it.cancel(false) }
        heldTimers.clear()
        wsClient?.close()
        scheduler.shutdownNow()
    }
}
