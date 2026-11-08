package com.tgwsproxy.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Локальный IPv4 телефона в Wi-Fi / Ethernet / точке доступа, либо null. */
    fun lanIpv4(context: Context): String? {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            if (cm != null && net != null) {
                val caps = cm.getNetworkCapabilities(net)
                if (caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                ) {
                    val ip = cm.getLinkProperties(net)?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                        ?.hostAddress
                    if (!ip.isNullOrEmpty()) return ip
                }
            }
        } catch (_: Exception) {
        }
        return fromInterfaces()
    }

    private fun fromInterfaces(): String? = try {
        val found = ArrayList<Pair<String, String>>()
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (ni in all) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses.toList()) {
                val host = addr.hostAddress
                if (addr is Inet4Address && addr.isSiteLocalAddress && !host.isNullOrEmpty()) found += ni.name to host
            }
        }
        (found.firstOrNull { it.first.startsWith("wlan") }
            ?: found.firstOrNull { it.first.startsWith("eth") || it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?: found.firstOrNull { !it.first.startsWith("rmnet") && !it.first.startsWith("tun") && !it.first.startsWith("ccmni") })
            ?.second
    } catch (_: Exception) {
        null
    }
}
