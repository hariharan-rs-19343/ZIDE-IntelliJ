package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import java.nio.file.Files
import kotlin.io.path.exists

class ClearAppLogsAction : AnAction(
    "Clear App Logs",
    "Delete application log files and clear the App Logs console",
    null
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val servers = TomcatServerProvider.getInstance(project).getServers()
        val serverRunning = servers.any { it.status == "running" || PortUtil.isPortInUse(it.port) }
        if (serverRunning) {
            Messages.showErrorDialog(
                project,
                "Cannot clear application logs while the server is running. Stop the server first.",
                "Clear App Logs"
            )
            return
        }

        val logsDir = AppLogsAction.resolveLogsDir(project)
        val deleted = mutableListOf<String>()
        if (logsDir != null && logsDir.exists()) {
            for (file in AppLogsAction.listApplicationLogFiles(logsDir)) {
                try {
                    Files.deleteIfExists(file)
                    deleted.add(file.fileName.toString())
                } catch (ex: Exception) {
                    NotificationUtil.warn(project, "Failed to delete ${file.fileName}: ${ex.message}")
                }
            }
        }

        val tomcatManager = TomcatManager.getInstance(project)
        tomcatManager.ensureToolWindow {
            val console = tomcatManager.appLogsConsoleView ?: return@ensureToolWindow
            console.clear()
            console.print("Application logs cleared.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        if (deleted.isEmpty()) {
            NotificationUtil.warn(project, "No application log files found to delete.")
        } else {
            NotificationUtil.info(project, "Application logs cleared.")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
