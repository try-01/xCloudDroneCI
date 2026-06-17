package com.tvremote.app

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
    private const val SCAN_TIMEOUT_MS = 1200L
    private const val MAX_CONCURRENT = 40

    data class TvInfo(val ip: String, val mac: String)

    suspend fun scan(): TvInfo? = withContext(Dispatchers.IO) {
        val baseIPs = getLocalSubnets()
        if (baseIPs.isEmpty()) {
            Log.w(TAG, "No local network interfaces found")
            return@withContext null
        }

        Log.d(TAG, "Scanning subnets: $baseIPs")

        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)
        val results = mutableListOf<TvInfo>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        val jobs = baseIPs.flatMap { base ->
            (1..254).map { i ->
                async {
                    val ip = "$base$i"
                    semaphore.acquire()
                    try {
                        scanSingle(ip)?.let { info ->
                            mutex.lock()
                            results.add(info)
                            mutex.unlock()
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        val first = results.firstOrNull()
        if (first != null) {
            Log.d(TAG, "TV found at ${first.ip} (MAC: ${first.mac})")
        } else {
            Log.w(TAG, "No TV found on network")
        }
        first
    }

    private fun getLocalSubnets(): List<String> {
        val subnets = mutableSetOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback || !iface.supportsMulticast()) continue
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    val octets = ip.split(".")
                    if (octets.size != 4) continue
                    // Skip APIPA
                    if (octets[0] == "169" && octets[1] == "254") continue
                    subnets.add("${octets[0]}.${octets[1]}.${octets[2]}.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating interfaces: ${e.message}")
        }
        return subnets.toList()
    }

    private fun scanSingle(ip: String): TvInfo? {
        return try {
            val url = URL("http://$ip:8001/api/v2/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = SCAN_TIMEOUT_MS.toInt()
            conn.readTimeout = SCAN_TIMEOUT_MS.toInt()
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val device = json.optJSONObject("device") ?: return null

            var mac = device.optString("wifiMac", "")
            if (mac.isEmpty()) mac = device.optString("mac", "")
            if (mac.isEmpty()) return null

            TvInfo(ip, mac)
        } catch (_: Exception) {
            null
        }
    }
}
