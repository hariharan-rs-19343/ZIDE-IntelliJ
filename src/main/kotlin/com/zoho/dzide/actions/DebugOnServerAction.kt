package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zoho.dzide.debug.DebuggerAttachUtil
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil

class DebugOnServerAction : AnAction("Debug", "Start server in debug mode and attach debugger", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: run {
            NotificationUtil.error(project, "No Tomcat server configured. Add a server first via ZIDE > Add Tomcat Server.")
            return
        }
        val tomcatManager = TomcatManager.getInstance(project)

        val debugPort = server.debugPort?.takeIf { it > 0 }
            ?: PortUtil.findAvailablePort(8000)

        try {
            tomcatManager.startServerInDebug(server, debugPort)
            // JPDA_SUSPEND=y — attach so the JVM can resume and hit startup breakpoints.
            DebuggerAttachUtil.attachAfterDelay(project, server, debugPort)
        } catch (ex: Exception) {
            NotificationUtil.error(project, "Debug failed: ${ex.message}")
        }
    }
}
