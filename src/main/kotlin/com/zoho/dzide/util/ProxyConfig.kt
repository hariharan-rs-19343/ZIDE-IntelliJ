package com.zoho.dzide.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.Properties

object ProxyConfig {

    private val log = Logger.getInstance(ProxyConfig::class.java)

    private val config: ProxySettings by lazy { loadConfig() }

    val isEnabled: Boolean get() = config.enabled
    val host: String get() = config.host
    val port: Int get() = config.port

    fun applyToSystemProperties() {
        if (!isEnabled) return
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", port.toString())
        System.setProperty("http.proxyHost", host)
        System.setProperty("http.proxyPort", port.toString())
        log.info("Proxy configured: $host:$port")
    }

    private fun loadConfig(): ProxySettings {
        val configDir = File(System.getProperty("user.home"), ".dzide")
        val configFile = File(configDir, "proxy.properties")

        if (!configFile.exists()) return ProxySettings.DISABLED

        return try {
            val props = Properties()
            configFile.inputStream().use { props.load(it) }

            val enabled = props.getProperty("proxy.enabled", "false").toBooleanStrictOrNull() ?: false
            val host = props.getProperty("proxy.host", "").trim()
            val port = props.getProperty("proxy.port", "0").trim().toIntOrNull() ?: 0

            if (enabled && host.isNotEmpty() && port > 0) {
                ProxySettings(true, host, port)
            } else {
                ProxySettings.DISABLED
            }
        } catch (e: Exception) {
            log.warn("Failed to read proxy config from ${configFile.absolutePath}", e)
            ProxySettings.DISABLED
        }
    }

    private data class ProxySettings(val enabled: Boolean, val host: String, val port: Int) {
        companion object {
            val DISABLED = ProxySettings(false, "", 0)
        }
    }
}
