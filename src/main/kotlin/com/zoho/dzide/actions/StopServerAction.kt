package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil

class StopServerAction : AnAction("Stop", "Stop Tomcat server", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: run {
            NotificationUtil.error(project, "No Tomcat server configured. Add a server first via ZIDE > Add Tomcat Server.")
            return
        }
        TomcatManager.getInstance(project).stopServer(server)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val servers = TomcatServerProvider.getInstance(project).getServers()
        val anyRunning = servers.any { it.status == "running" || PortUtil.isPortInUse(it.port) }
        e.presentation.isEnabled = anyRunning
    }
}
