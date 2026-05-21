package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.NotificationUtil

class StartServerAction : AnAction("Start", "Start Tomcat server", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: run {
            NotificationUtil.error(project, "No Tomcat server configured. Add a server first via ZIDE > Add Tomcat Server.")
            return
        }
        TomcatManager.getInstance(project).startServer(server)
    }
}
