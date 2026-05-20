package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.NotificationUtil
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

object ServerActionUtil {

    fun getSelectedServer(e: AnActionEvent): TomcatServer? {
        val project = e.project ?: return null

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
        if (toolWindow != null) {
            val content = toolWindow.contentManager.selectedContent
            if (content != null) {
                val tree = findTree(content.component)
                if (tree != null) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val server = node?.userObject as? TomcatServer
                    if (server != null) return server
                }
            }
        }

        val serverProvider = TomcatServerProvider.getInstance(project)
        val servers = serverProvider.getServers()
        if (servers.isEmpty()) {
            NotificationUtil.error(project, "No Tomcat servers configured. Please add a server first.")
            return null
        }
        if (servers.size == 1) return servers[0]

        val serverNames = servers.map { "${it.name} (port ${it.port}) — ${it.status}" }.toTypedArray()
        val selectedIndex = Messages.showChooseDialog(
            project,
            "Select the Tomcat server to use:",
            "Select Tomcat Server",
            null,
            serverNames,
            serverNames[0]
        )
        if (selectedIndex < 0) return null
        return servers[selectedIndex]
    }

    private fun findTree(component: java.awt.Component): JTree? {
        if (component is JTree) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                val found = findTree(child)
                if (found != null) return found
            }
        }
        return null
    }
}
