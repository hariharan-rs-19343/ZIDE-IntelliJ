package com.zoho.dzide.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxySettings
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.settings.ZideSettingsState
import java.net.URI

/** IntelliJ proxy before the Zero Agent proxy replaced it. */
data class SavedIdeProxy(
    val kind: String,
    val protocol: String = "",
    val host: String = "",
    val port: Int = 0,
    val exceptions: String = "",
    val pacUrl: String = ""
) {
    fun encode(): String = when (kind) {
        KIND_STATIC -> listOf(kind, protocol, host, port.toString(), exceptions).joinToString(SEP)
        KIND_PAC -> listOf(kind, pacUrl).joinToString(SEP)
        else -> kind
    }

    companion object {
        const val KIND_DIRECT = "DIRECT"
        const val KIND_AUTODETECT = "AUTODETECT"
        const val KIND_STATIC = "STATIC"
        const val KIND_PAC = "PAC"
        private const val SEP = "|"

        fun decode(raw: String?): SavedIdeProxy? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEP, limit = 5)
            return when (parts[0]) {
                KIND_DIRECT, KIND_AUTODETECT -> SavedIdeProxy(parts[0])
                KIND_PAC -> parts.getOrNull(1)?.let { SavedIdeProxy(KIND_PAC, pacUrl = it) }
                KIND_STATIC -> {
                    if (parts.size < 4) return null
                    val port = parts[3].toIntOrNull() ?: return null
                    SavedIdeProxy(KIND_STATIC, parts[1], parts[2], port, parts.getOrElse(4) { "" })
                }
                else -> null
            }
        }
    }
}

enum class IdeProxyAction { APPLY, RESTORE, NONE }

object ZeroProxyManager {

    private val log = Logger.getInstance(ZeroProxyManager::class.java)

    fun decideIdeAction(
        useZeroTrustProxy: Boolean,
        agentOn: Boolean,
        unalterProxy: Boolean,
        applied: Boolean
    ): IdeProxyAction = when {
        unalterProxy -> IdeProxyAction.NONE
        useZeroTrustProxy && agentOn -> IdeProxyAction.APPLY
        applied -> IdeProxyAction.RESTORE
        else -> IdeProxyAction.NONE
    }

    fun isActive(): Boolean {
        if (ApplicationManager.getApplication() == null) return false
        return ZideSettingsState.getInstance().useZeroTrustProxy && ZeroProxy.isAgentOn()
    }

    /** Environment additions for git, wget, and Ant child processes. */
    fun processEnv(): Map<String, String> = ZeroProxy.childEnv(ZeroProxy.endpoint, isActive())

    fun resolveEndpoint(moduleZidePropertiesPath: String?, zideRepoPath: String?): ZeroProxy.Endpoint {
        val endpoint = ZeroProxy.endpointFromVmArgs(
            ZeroProxy.readProxyVmArgs(moduleZidePropertiesPath, zideRepoPath)
        )
        ZeroProxy.endpoint = endpoint
        return endpoint
    }

    fun resolveEndpointForProject(projectPath: String?, server: TomcatServer?): ZeroProxy.Endpoint {
        val repo = server?.zideFolderPath
            ?: projectPath?.let { PathResolver.resolveZideConfigRepoFromProject(it) }
        return resolveEndpoint(server?.zidePropertiesPath, repo)
    }

    /** Proxy VM args for Tomcat while the Zero Agent is on, else null. */
    fun launchProxyArgs(server: TomcatServer): String? {
        if (!isActive()) return null
        val vmArgs = ZeroProxy.readProxyVmArgs(server.zidePropertiesPath, server.zideFolderPath)
        ZeroProxy.endpoint = ZeroProxy.endpointFromVmArgs(vmArgs)
        return vmArgs ?: ZeroProxy.vmArgsFor(ZeroProxy.endpoint)
    }

    /**
     * Points the IntelliJ HTTP proxy at the Zero Agent, or restores the saved proxy once the
     * agent is off. Returns a user-facing message when the IDE proxy changed.
     */
    fun syncIdeProxy(): String? {
        val settings = ZideSettingsState.getInstance()
        val action = decideIdeAction(
            useZeroTrustProxy = settings.useZeroTrustProxy,
            agentOn = ZeroProxy.isAgentOn(),
            unalterProxy = ZeroProxy.isUnalterProxy(),
            applied = settings.zeroProxyApplied
        )
        return try {
            when (action) {
                IdeProxyAction.APPLY -> applyIdeProxy(settings)
                IdeProxyAction.RESTORE -> restoreIdeProxy(settings)
                IdeProxyAction.NONE -> null
            }
        } catch (e: Exception) {
            log.warn("ZeroTrust proxy: failed to update IntelliJ proxy", e)
            null
        }
    }

    private fun applyIdeProxy(settings: ZideSettingsState): String? {
        val proxySettings = ProxySettings.getInstance()
        val current = proxySettings.getProxyConfiguration()
        val endpoint = ZeroProxy.endpoint
        val alreadySet = isZeroProxy(current, endpoint)
        if (!settings.zeroProxyApplied) {
            settings.savedIdeProxy = snapshot(current).encode()
            settings.zeroProxyApplied = true
        }
        if (alreadySet) return null
        proxySettings.setProxyConfiguration(
            ProxyConfiguration.proxy(ProxyConfiguration.ProxyProtocol.HTTP, endpoint.host, endpoint.port, ZeroProxy.BYPASS)
        )
        log.info("ZeroTrust proxy: IntelliJ HTTP proxy set to ${endpoint.host}:${endpoint.port}")
        return "Zero Agent is on. IntelliJ HTTP proxy set to ${endpoint.host}:${endpoint.port} " +
            "(bypass ${ZeroProxy.BYPASS})."
    }

    private fun restoreIdeProxy(settings: ZideSettingsState): String? {
        val proxySettings = ProxySettings.getInstance()
        val current = proxySettings.getProxyConfiguration()
        val saved = SavedIdeProxy.decode(settings.savedIdeProxy)
        settings.zeroProxyApplied = false
        settings.savedIdeProxy = ""
        // Leave a proxy the user changed by hand while the agent was on.
        if (!isZeroProxyShape(current)) return null
        val restored = saved?.let { toConfiguration(it) } ?: ProxyConfiguration.direct
        proxySettings.setProxyConfiguration(restored)
        log.info("ZeroTrust proxy: restored previous IntelliJ proxy (${saved?.kind ?: SavedIdeProxy.KIND_DIRECT})")
        return "Zero Agent is off. Restored the previous IntelliJ proxy settings."
    }

    private fun isZeroProxyShape(config: ProxyConfiguration): Boolean =
        config is ProxyConfiguration.StaticProxyConfiguration &&
            config.protocol == ProxyConfiguration.ProxyProtocol.HTTP &&
            config.exceptions.trim() == ZeroProxy.BYPASS

    private fun isZeroProxy(config: ProxyConfiguration, endpoint: ZeroProxy.Endpoint): Boolean =
        isZeroProxyShape(config) &&
            (config as ProxyConfiguration.StaticProxyConfiguration).host == endpoint.host &&
            config.port == endpoint.port

    fun snapshot(config: ProxyConfiguration): SavedIdeProxy = when (config) {
        is ProxyConfiguration.StaticProxyConfiguration -> SavedIdeProxy(
            SavedIdeProxy.KIND_STATIC, config.protocol.name, config.host, config.port, config.exceptions
        )
        is ProxyConfiguration.ProxyAutoConfiguration ->
            SavedIdeProxy(SavedIdeProxy.KIND_PAC, pacUrl = config.pacUrl.toString())
        is ProxyConfiguration.AutoDetectProxy -> SavedIdeProxy(SavedIdeProxy.KIND_AUTODETECT)
        else -> SavedIdeProxy(SavedIdeProxy.KIND_DIRECT)
    }

    fun toConfiguration(saved: SavedIdeProxy): ProxyConfiguration = when (saved.kind) {
        SavedIdeProxy.KIND_STATIC -> ProxyConfiguration.proxy(
            runCatching { ProxyConfiguration.ProxyProtocol.valueOf(saved.protocol) }
                .getOrDefault(ProxyConfiguration.ProxyProtocol.HTTP),
            saved.host,
            saved.port,
            saved.exceptions
        )
        SavedIdeProxy.KIND_PAC -> runCatching { ProxyConfiguration.proxyAutoConfiguration(URI(saved.pacUrl).toURL()) }
            .getOrElse { ProxyConfiguration.direct }
        SavedIdeProxy.KIND_AUTODETECT -> ProxyConfiguration.autodetect
        else -> ProxyConfiguration.direct
    }
}
