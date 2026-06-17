package com.tvremote.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {

    private const val TAG = "WakeOnLan"

    fun send(macAddress: String, targetIp: String) {
        try {
            val macBytes = parseMac(macAddress)
            if (macBytes == null) {
                Log.e(TAG, "Invalid MAC address: $macAddress")
                return
            }

            // Build magic packet: 6 bytes 0xFF + 16 repetitions of MAC
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
            }

            val broadcast = subnetBroadcast(targetIp)
            val targets = listOf("255.255.255.255", broadcast, targetIp)
            val ports = listOf(9, 7)

            val socket = DatagramSocket()
            socket.broadcast = true

            for (attempt in 1..5) {
                for (target in targets) {
                    for (port in ports) {
                        try {
                            val addr = InetAddress.getByName(target)
                            val dp = DatagramPacket(packet, packet.size, addr, port)
                            socket.send(dp)
                        } catch (_: Exception) {}
                    }
                }
                Thread.sleep(500)
            }

            socket.close()
            Log.d(TAG, "WoL packets sent (5 rounds)")
        } catch (e: Exception) {
            Log.e(TAG, "WoL failed: ${e.message}")
        }
    }

    private fun parseMac(mac: String): ByteArray? {
        val clean = mac.replace(":", "").replace("-", "")
        if (clean.length != 12) return null
        return try {
            ByteArray(6) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun subnetBroadcast(ip: String): String {
        val i = ip.lastIndexOf(".")
        return if (i >= 0) ip.substring(0, i) + ".255" else "255.255.255.255"
    }
}
