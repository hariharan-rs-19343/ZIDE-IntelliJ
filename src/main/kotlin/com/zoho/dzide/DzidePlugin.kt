package com.zoho.dzide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zoho.dzide.settings.CmToolConfigurable
import com.zoho.dzide.settings.CredentialResolver
import com.zoho.dzide.settings.WgetConfigurable
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.update.PluginUpdateChecker
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProxyConfig
import com.zoho.dzide.util.ZeroProxyManager
import com.zoho.dzide.zide.ZideConfigParser
import com.zoho.dzide.zide.ZideConfigRepoService
import java.io.File

class DzidePlugin : ProjectActivity {

    private val log = Logger.getInstance(DzidePlugin::class.java)

    override suspend fun execute(project: Project) {
        log.info("DZIDE plugin initialized for project: ${project.name}")

        ProxyConfig.applyToSystemProperties()
        syncZeroTrustProxy(project)

        warnMissingMandatoryCredentials(project)

        autoConfigureLibraries(project)

        ApplicationManager.getApplication().executeOnPooledThread {
            updateZideConfigRepo(project)
            val updateInfo = PluginUpdateChecker.checkForUpdate()
            if (updateInfo != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        PluginUpdateChecker.showUpdateNotification(project, updateInfo)
                    }
                }
            }
        }
    }

    /** CMTool and Wget are mandatory: Settings value, else global (~/.zide/zide.conf, ~/.wgetrc / ~/.netrc). */
    private fun warnMissingMandatoryCredentials(project: Project) {
        if (CredentialResolver.resolveWget() == null) {
            NotificationUtil.warn(
                project,
                "Wget credential not found in Settings, ~/.wgetrc or ~/.netrc. " +
                    "Configure it in Settings > Tools > Zide > Wget Configuration.",
                NotificationUtil.openSettingsAction(WgetConfigurable::class.java)
            )
        }
        if (CredentialResolver.resolveCmToolToken() == null) {
            NotificationUtil.warn(
                project,
                "CMTool Auth Token not found in Settings or ~/.zide/zide.conf. " +
                    "Configure it in Settings > Tools > Zide > CMTool.",
                NotificationUtil.openSettingsAction(CmToolConfigurable::class.java)
            )
        }
    }

    private fun syncZeroTrustProxy(project: Project) {
        val server = TomcatServerProvider.getInstance(project).getServers().firstOrNull()
        ZeroProxyManager.resolveEndpointForProject(project.basePath, server)
        ApplicationManager.getApplication().invokeLater {
            val message = ZeroProxyManager.syncIdeProxy() ?: return@invokeLater
            if (!project.isDisposed) NotificationUtil.info(project, message)
        }
    }

    private fun updateZideConfigRepo(project: Project) {
        val projectPath = project.basePath ?: return
        if (!ZideConfigParser.detectZideConfigInProject(projectPath)) return
        val workspace = File(projectPath).parentFile ?: return
        val result = ZideConfigRepoService.ensureCloned(workspace)
        if (!result.success) {
            log.warn("ZIDE config repo update skipped: ${result.message}")
        } else {
            log.info("ZIDE config repo ready: ${result.message}")
        }
    }

    private fun autoConfigureLibraries(project: Project) {
        val projectPath = project.basePath ?: return
        ZideConfigParser.readZideConfig(projectPath) ?: return
        val serverProvider = TomcatServerProvider.getInstance(project)
        val server = serverProvider.getServers().firstOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            TomcatManager.getInstance(project).configureProjectLibraries(server)
        }
    }
}
