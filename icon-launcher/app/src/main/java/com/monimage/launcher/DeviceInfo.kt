package com.monimage.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale

/** Infos du téléphone affichées dans la boîte à outils. */
object DeviceInfo {

    /** Une ligne « titre : valeur ». */
    data class Row(val title: String, val value: String)

    /** Infos locales, instantanées. */
    fun collect(context: Context): List<Row> = buildList {
        addAll(network(context))
        add(Row("Appareil", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${Build.DEVICE})"))
        add(Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · patch ${Build.VERSION.SECURITY_PATCH}"))
        add(Row("Processeur", "${Runtime.getRuntime().availableProcessors()} cœurs · ${Build.SUPPORTED_ABIS.joinToString()}"))
        add(Row("Batterie", battery(context)))
        add(Row("Mémoire vive", ram(context)))
        add(Row("Stockage", storage()))
        val dm = context.resources.displayMetrics
        add(Row("Écran", "${dm.widthPixels} × ${dm.heightPixels} px · ${dm.densityDpi} dpi"))
        add(Row("Allumé depuis", duration(SystemClock.elapsedRealtime())))
    }

    /** IP publique (vue depuis Internet). À appeler hors du fil principal. */
    fun publicIp(): String {
        for (url in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")) {
            val ip = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "curl/8")
                conn.inputStream.bufferedReader().use { it.readText().trim() }.also { conn.disconnect() }
            }.getOrNull()
            if (!ip.isNullOrBlank() && ip.length < 64) return ip
        }
        return "indisponible (pas d'Internet ?)"
    }

    private fun network(context: Context): List<Row> = buildList {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "Hors ligne"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Autre"
        }
        val vpn = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) " · VPN actif" else ""
        val speed = caps?.let { " · ↓ ${it.linkDownstreamBandwidthKbps / 1000} Mb/s ↑ ${it.linkUpstreamBandwidthKbps / 1000} Mb/s" } ?: ""
        add(Row("Réseau", type + vpn + speed))

        val v4 = mutableListOf<String>()
        val v6 = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nif ->
                    nif.inetAddresses.toList().forEach { addr ->
                        when (addr) {
                            is Inet4Address -> v4 += "${addr.hostAddress} (${nif.name})"
                            is Inet6Address -> if (!addr.isLinkLocalAddress) {
                                v6 += "${addr.hostAddress?.substringBefore('%')} (${nif.name})"
                            }
                        }
                    }
                }
        }
        add(Row("IP locale (IPv4)", v4.joinToString("\n").ifEmpty { "aucune" }))
        if (v6.isNotEmpty()) add(Row("IP locale (IPv6)", v6.joinToString("\n")))
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "inconnue"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "en charge"
            BatteryManager.BATTERY_STATUS_FULL -> "pleine"
            else -> "sur batterie"
        }
        return String.format(Locale.FRANCE, "%d %% · %s · %.1f °C · %.2f V", level * 100 / scale, charging, temp, volts)
    }

    private fun ram(context: Context): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return "${gb(info.totalMem - info.availMem)} utilisés / ${gb(info.totalMem)}"
    }

    private fun storage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return "${gb(stat.totalBytes - stat.availableBytes)} utilisés / ${gb(stat.totalBytes)} (${gb(stat.availableBytes)} libres)"
    }

    private fun gb(bytes: Long) = String.format(Locale.FRANCE, "%.1f Go", bytes / 1_073_741_824.0)

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val days = minutes / (60 * 24)
        val hours = minutes / 60 % 24
        return if (days > 0) "${days} j ${hours} h ${minutes % 60} min" else "${hours} h ${minutes % 60} min"
    }
}
