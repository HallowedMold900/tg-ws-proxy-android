package com.tgwsproxy.data

import com.tgwsproxy.R
import com.tgwsproxy.S
import android.content.Context
import java.net.InetAddress
import java.security.SecureRandom

enum class Field { HOST, PORT, SECRET, DC_IP, CF_DOMAIN, WORKER_DOMAIN, FAKE_TLS, ALLOWED }

/** Правило доступа: один IPv4 или подсеть CIDR. */
class IpRule(private val base: Int, private val mask: Int) {
    fun matches(ip: Int): Boolean = (ip and mask) == (base and mask)

    companion object {
        fun parseIp(s: String): Int? {
            val p = s.split('.')
            if (p.size != 4) return null
            var r = 0
            for (x in p) {
                val v = x.toIntOrNull() ?: return null
                if (v !in 0..255) return null
                r = (r shl 8) or v
            }
            return r
        }

        fun parse(text: String): IpRule? {
            val parts = text.split('/')
            if (parts.size > 2) return null
            val ip = parseIp(parts[0]) ?: return null
            val bits = if (parts.size == 2) (parts[1].toIntOrNull() ?: return null) else 32
            if (bits !in 0..32) return null
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            return IpRule(ip, mask)
        }
    }
}

/** Проверенные настройки, с которыми запускается прокси. */
class ProxySettings(
    val host: String,
    val port: Int,
    val secretHex: String,
    val dcIp: Map<Int, String>,
    val cfProxy: Boolean,
    val cfProxyDomains: List<String>,
    val cfWorker: Boolean,
    val cfWorkerDomains: List<String>,
    val cfH2: Boolean,
    val fakeTlsDomain: String,
    val allowed: List<IpRule>,
) {
    val secret: ByteArray = hexToBytes(secretHex)

    val link: String
        get() {
            val server = if (host == "0.0.0.0") "127.0.0.1" else host
            val s = if (fakeTlsDomain.isNotEmpty()) {
                "ee$secretHex" + fakeTlsDomain.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            } else {
                "dd$secretHex"
            }
            return "tg://proxy?server=$server&port=$port&secret=$s"
        }

    /** Белый список адресов (пустой — разрешены все). Своё устройство разрешено всегда. */
    fun isAllowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (allowed.isEmpty()) return true
        val b = addr.address
        if (b.size != 4) return false
        var ip = 0
        for (x in b) ip = (ip shl 8) or (x.toInt() and 0xff)
        return allowed.any { it.matches(ip) }
    }
}

class Validation(val settings: ProxySettings?, val errors: Map<Field, String>)

/** Сырые значения из полей ввода (строки), чтобы можно было печатать невалидный текст. */
data class Draft(
    val host: String = "127.0.0.1",
    val port: String = "1443",
    val secret: String = "",
    val dcIp: String = "2:149.154.167.220\n4:149.154.167.220",
    val cfProxy: Boolean = true,
    val cfProxyDomain: String = "",
    val cfWorker: Boolean = false,
    val cfWorkerDomain: String = "",
    val cfH2: Boolean = false,
    val fakeTlsDomain: String = "",
    val allowedIps: String = "",
    val autostart: Boolean = false,
    val restartOnNetwork: Boolean = true,
    val dynamicColors: Boolean = false,
) {
    /** Всё, что влияет на уже запущенный прокси (без автозапуска и оформления). */
    fun runtimeKey(): List<Any> = listOf(
        host.trim(), port.trim(), secret.trim().lowercase(), dcIp.trim(),
        cfProxy, cfProxyDomain.trim(), cfWorker, cfWorkerDomain.trim(),
        cfH2, fakeTlsDomain.trim(), allowedIps.trim(),
    )

    fun validate(): Validation {
        val errors = LinkedHashMap<Field, String>()

        val h = host.trim()
        if (!IPV4.matches(h)) errors[Field.HOST] = S.get(R.string.err_host)

        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1024..65535) errors[Field.PORT] = S.get(R.string.err_port)

        var s = secret.trim().lowercase()
        if (s.length == 34 && s.startsWith("dd")) s = s.substring(2)
        if (s.length >= 34 && s.startsWith("ee")) {
            errors[Field.SECRET] = S.get(R.string.err_secret_ee)
        } else if (!HEX32.matches(s)) {
            errors[Field.SECRET] = S.get(R.string.err_secret)
        }

        val dcMap = LinkedHashMap<Int, String>()
        for (raw in dcIp.split(Regex("[\\n,;]+"))) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = DC_LINE.matchEntire(line)
            val dc = m?.groupValues?.get(1)?.toIntOrNull()
            val ip = m?.groupValues?.get(2)
            if (m == null || dc == null || ip == null || dc !in VALID_DCS || !IPV4.matches(ip)) {
                errors[Field.DC_IP] = S.get(R.string.err_dc_line, line)
                break
            }
            dcMap[dc] = ip
        }

        val cfDomains = parseDomains(cfProxyDomain)
        if (cfDomains == null) errors[Field.CF_DOMAIN] = S.get(R.string.err_domain)

        val workerDomains = parseDomains(cfWorkerDomain)
        if (workerDomains == null) {
            errors[Field.WORKER_DOMAIN] = S.get(R.string.err_worker_domain)
        } else if (cfWorker && workerDomains.isEmpty()) {
            errors[Field.WORKER_DOMAIN] = S.get(R.string.err_worker_empty)
        }

        val tlsDomains = parseDomains(fakeTlsDomain)
        if (tlsDomains == null || tlsDomains.size > 1) {
            errors[Field.FAKE_TLS] = S.get(R.string.err_fake_tls)
        }

        val rules = ArrayList<IpRule>()
        for (raw in allowedIps.split(Regex("[\\s,;]+"))) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val r = IpRule.parse(t)
            if (r == null) {
                errors[Field.ALLOWED] = S.get(R.string.err_allowed, t)
                break
            }
            rules += r
        }

        if (errors.isNotEmpty()) return Validation(null, errors)
        return Validation(
            ProxySettings(
                host = h, port = p!!, secretHex = s, dcIp = dcMap,
                cfProxy = cfProxy, cfProxyDomains = cfDomains.orEmpty(),
                cfWorker = cfWorker, cfWorkerDomains = workerDomains.orEmpty(),
                cfH2 = cfH2,
                fakeTlsDomain = tlsDomains.orEmpty().firstOrNull().orEmpty(),
                allowed = rules,
            ),
            emptyMap(),
        )
    }

    companion object {
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
        private val HEX32 = Regex("^[0-9a-f]{32}$")
        private val DC_LINE = Regex("^(\\d{1,3})\\s*:\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})$")
        private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
        val VALID_DCS = setOf(1, 2, 3, 4, 5, 203)

        /** null — есть невалидный домен; пустой список — поле пустое. */
        private fun parseDomains(text: String): List<String>? {
            val out = ArrayList<String>()
            for (raw in text.split(Regex("[\\s,;]+"))) {
                var d = raw.trim().lowercase()
                if (d.isEmpty()) continue
                d = d.removePrefix("https://").removePrefix("http://").substringBefore('/')
                if (!DOMAIN.matches(d)) return null
                out += d
            }
            return out
        }
    }
}

fun generateSecret(): String {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Draft {
        val def = Draft()
        var d = Draft(
            host = prefs.getString("host", def.host) ?: def.host,
            port = prefs.getString("port", def.port) ?: def.port,
            secret = prefs.getString("secret", "") ?: "",
            dcIp = prefs.getString("dcIp", def.dcIp) ?: def.dcIp,
            cfProxy = prefs.getBoolean("cfProxy", def.cfProxy),
            cfProxyDomain = prefs.getString("cfProxyDomain", "") ?: "",
            cfWorker = prefs.getBoolean("cfWorker", def.cfWorker),
            cfWorkerDomain = prefs.getString("cfWorkerDomain", "") ?: "",
            cfH2 = prefs.getBoolean("cfH2", def.cfH2),
            fakeTlsDomain = prefs.getString("fakeTlsDomain", "") ?: "",
            allowedIps = prefs.getString("allowedIps", "") ?: "",
            autostart = prefs.getBoolean("autostart", def.autostart),
            restartOnNetwork = prefs.getBoolean("restartOnNetwork", def.restartOnNetwork),
            dynamicColors = prefs.getBoolean("dynamicColors", def.dynamicColors),
        )
        if (d.secret.isBlank()) {
            d = d.copy(secret = generateSecret())
            save(d)
        }
        return d
    }

    fun save(d: Draft) {
        prefs.edit()
            .putString("host", d.host)
            .putString("port", d.port)
            .putString("secret", d.secret)
            .putString("dcIp", d.dcIp)
            .putBoolean("cfProxy", d.cfProxy)
            .putString("cfProxyDomain", d.cfProxyDomain)
            .putBoolean("cfWorker", d.cfWorker)
            .putString("cfWorkerDomain", d.cfWorkerDomain)
            .putBoolean("cfH2", d.cfH2)
            .putString("fakeTlsDomain", d.fakeTlsDomain)
            .putString("allowedIps", d.allowedIps)
            .putBoolean("autostart", d.autostart)
            .putBoolean("restartOnNetwork", d.restartOnNetwork)
            .putBoolean("dynamicColors", d.dynamicColors)
            .apply()
    }
}
