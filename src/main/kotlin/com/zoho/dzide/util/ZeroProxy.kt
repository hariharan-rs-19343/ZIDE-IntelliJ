package com.zoho.dzide.util

import com.zoho.dzide.parser.ModuleZidePropsParser
import java.io.File
import java.nio.file.Path

/**
 * ZeroTrust Zero Agent proxy detection and the values derived from it
 * (Eclipse SystemUtil.getZeroAgentStatus + launch.proxy.vmargs).
 */
object ZeroProxy {

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 3128
    const val BYPASS = "localhost,127.0.0.1"
    const val UNALTER_PROXY_PROPERTY = "zide.unalterproxy"
    const val LAUNCH_PROXY_VMARGS_KEY = "launch.proxy.vmargs"
    private const val STATUS_KEY = "ZERO_HELPER_STATUS"

    data class Endpoint(val host: String = DEFAULT_HOST, val port: Int = DEFAULT_PORT) {
        val url: String get() = "http://$host:$port"
    }

    val DEFAULT_ENDPOINT = Endpoint()

    @Volatile
    var endpoint: Endpoint = DEFAULT_ENDPOINT

    private val assignmentRegex =
        Regex("""^\s*(?:export\s+)?$STATUS_KEY\s*=\s*["']?([A-Za-z]+)["']?\s*(?:#.*)?$""")
    private val hostRegex = Regex("""-Dhttps?\.proxyHost=(\S+)""")
    private val portRegex = Regex("""-Dhttps?\.proxyPort=(\d+)""")

    fun isAgentOn(
        home: File = File(System.getProperty("user.home")),
        osName: String = System.getProperty("os.name") ?: "",
        env: Map<String, String> = System.getenv()
    ): Boolean {
        env[STATUS_KEY]?.let { return it.trim().equals("ON", ignoreCase = true) }
        if (osName.lowercase().contains("win")) return false
        val profile = profileFile(home, osName) ?: return false
        val content = try { profile.readText() } catch (_: Exception) { return false }
        return parseHelperStatus(content)
    }

    /** Eclipse reads a single profile: Mac `.zshrc` → `.bash_profile` → `.bashrc`; Linux `.bashrc`. */
    fun profileFile(home: File, osName: String): File? {
        val candidates = if (osName.lowercase().contains("mac")) {
            listOf(".zshrc", ".bash_profile", ".bashrc")
        } else {
            listOf(".bashrc")
        }
        return candidates.map { File(home, it) }.firstOrNull { it.isFile }
    }

    /** Last uncommented `ZERO_HELPER_STATUS=` assignment wins. */
    fun parseHelperStatus(content: String): Boolean {
        var on = false
        for (line in content.lineSequence()) {
            val match = assignmentRegex.find(line) ?: continue
            on = match.groupValues[1].equals("ON", ignoreCase = true)
        }
        return on
    }

    fun isUnalterProxy(): Boolean =
        System.getProperty(UNALTER_PROXY_PROPERTY)?.trim().equals("true", ignoreCase = true)

    fun commonZidePropertiesPath(zideRepoPath: String?): String? =
        zideRepoPath?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it, "deployment", "zide", "Zide.properties").toString() }

    /** `launch.proxy.vmargs` from the module Zide.properties, else the shared one. */
    fun readProxyVmArgs(moduleZidePropertiesPath: String?, zideRepoPath: String?): String? {
        for (path in listOf(moduleZidePropertiesPath, commonZidePropertiesPath(zideRepoPath))) {
            val raw = ModuleZidePropsParser.readAllPropertiesFromFile(path)[LAUNCH_PROXY_VMARGS_KEY]
            val normalized = normalizeVmArgs(raw)
            if (normalized != null) return normalized
        }
        return null
    }

    fun normalizeVmArgs(raw: String?): String? {
        var value = raw?.trim() ?: return null
        while (value.length >= 2 &&
            ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value.ifEmpty { null }
    }

    fun endpointFromVmArgs(vmArgs: String?): Endpoint {
        if (vmArgs.isNullOrBlank()) return DEFAULT_ENDPOINT
        val host = hostRegex.find(vmArgs)?.groupValues?.get(1) ?: DEFAULT_HOST
        val port = portRegex.find(vmArgs)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_PORT
        return Endpoint(host, port)
    }

    fun vmArgsFor(endpoint: Endpoint): String =
        "-Dhttp.proxyHost=${endpoint.host} -Dhttp.proxyPort=${endpoint.port} " +
            "-Dhttps.proxyHost=${endpoint.host} -Dhttps.proxyPort=${endpoint.port}"

    /** Proxy variables for git, wget, and Ant, which ignore the IntelliJ proxy page. */
    fun childEnv(endpoint: Endpoint, active: Boolean): Map<String, String> {
        if (!active) return emptyMap()
        val url = endpoint.url
        return linkedMapOf(
            "http_proxy" to url,
            "https_proxy" to url,
            "no_proxy" to BYPASS,
            "HTTP_PROXY" to url,
            "HTTPS_PROXY" to url,
            "NO_PROXY" to BYPASS
        )
    }

    /** Appends [proxyArgs] to [launchArgs] unless a proxy host is already configured. */
    fun mergeLaunchArgs(launchArgs: String?, proxyArgs: String?): String? {
        val base = launchArgs?.trim().orEmpty()
        val proxy = proxyArgs?.trim().orEmpty()
        if (proxy.isEmpty() || base.contains("-Dhttp.proxyHost=")) return base.ifEmpty { null }
        return listOf(base, proxy).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
