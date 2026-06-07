package com.zoho.dzide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.update.PluginUpdateChecker
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProxyConfig
import com.zoho.dzide.zide.ZideConfigParser
import java.io.File

class DzidePlugin : ProjectActivity {

    private val log = Logger.getInstance(DzidePlugin::class.java)

    override suspend fun execute(project: Project) {
        log.info("DZIDE plugin initialized for project: ${project.name}")

        ProxyConfig.applyToSystemProperties()

        val wgetrcFile = File(System.getProperty("user.home"), ".wgetrc")
        if (!wgetrcFile.exists()) {
            NotificationUtil.warn(
                project,
                "~/.wgetrc file is missing. Configure Wget credentials in Settings > Tools > Zide > Wget Configuration."
            )
        }

        autoConfigureLibraries(project)

        ApplicationManager.getApplication().executeOnPooledThread {
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
