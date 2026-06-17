package com.tvremote.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import org.json.JSONObject

object NetworkScanner {

    private const val TAG = "NetworkScanner"
    private const val SCAN_TIMEOUT_MS = 250
    private const val MAX_CONCURRENT = 60

    data class TvInfo(val ip: String, val mac: String)

    /**
     * Scan local network for Samsung TV using HTTP port probing on port 8001.
     * Returns the first TV found, or null if none found.
     */
    suspend fun scan(context: Context): TvInfo? = withContext(Dispatchers.IO) {
        val baseIP = getDeviceSubnet(context)
        if (baseIP == null) {
            Log.w(TAG, "Could not determine device subnet")
            return@withContext null
        }

        Log.d(TAG, "Scanning subnet: ${baseIP}x (254 hosts, ${SCAN_TIMEOUT_MS}ms timeout)")

        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)
        val found = java.util.concurrent.ConcurrentLinkedQueue<TvInfo>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        val jobs = (1..254).map { i ->
            async {
                val ip = "$baseIP$i"
                semaphore.acquire()
                try {
                    probeTv(ip)?.let { info ->
                        if (found.isEmpty()) {
                            mutex.lock()
                            if (found.isEmpty()) {
                                found.add(info)
                                Log.d(TAG, "TV FOUND: ${info.ip} (MAC: ${info.mac})")
                            }
                            mutex.unlock()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    semaphore.release()
                }
            }
        }

        // Wait for all jobs to complete
        jobs.forEach { it.join() }

        val result = found.firstOrNull()
        if (result == null) {
            Log.w(TAG, "No Samsung TV found on subnet ${baseIP}x")
        }
        result
    }

    /**
     * Get the /24 subnet prefix from the device's WiFi IP.
     * e.g., "192.168.1." from "192.168.1.105"
     */
    private fun getDeviceSubnet(context: Context): String? {
        // Method 1: Use WifiManager (most reliable on Android)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wm.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.",
                    ipInt and 0xff,
                    (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff
                )
                Log.d(TAG, "Subnet from WifiManager: ${ip}x")
                return ip
            }
        } catch (e: Exception) {
            Log.d(TAG, "WifiManager failed: ${e.message}")
        }

        // Method 2: Use NetworkInterface enumeration
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    // Skip loopback and APIPA
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue
                    val parts = ip.split(".")
                    if (parts.size == 4) {
                        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                        Log.d(TAG, "Subnet from NetworkInterface: ${prefix}x")
                        return prefix
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "NetworkInterface failed: ${e.message}")
        }

        return null
    }

    /**
     * Probe a single IP on port 8001 to check if it's a Samsung TV.
     * Returns TvInfo if found, null otherwise.
     */
    private fun probeTv(ip: String): TvInfo? {
        return try {
            val url = URL("http://$ip:8001/api/v2/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = SCAN_TIMEOUT_MS
            conn.readTimeout = SCAN_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val device = json.optJSONObject("device") ?: return null

            // Get MAC address (try wifiMac first, then mac)
            var mac = device.optString("wifiMac", "")
            if (mac.isEmpty()) mac = device.optString("mac", "")

            // Check if this looks like a Samsung TV
            val model = device.optString("model", "")
            val manufacturer = device.optString("manufacturer", "")
            val powerState = device.optString("PowerState", "")
            val deviceId = device.optString("id", "")

            // Accept if we got a valid response with device info
            if (deviceId.isEmpty() && mac.isEmpty()) return null

            Log.d(TAG, "Probed $ip: model=$model, mfr=$manufacturer, mac=$mac")
            TvInfo(ip, mac)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if a specific IP is a reachable Samsung TV.
     */
    suspend fun probeSingle(ip: String): TvInfo? = withContext(Dispatchers.IO) {
        probeTv(ip)
    }
}
