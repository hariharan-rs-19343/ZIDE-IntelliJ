package com.zoho.dzide.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPopupMenu
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.tomcat.TomcatServerProvider
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

object TomcatTreePopupHandler {

    @Suppress("UNUSED_PARAMETER")
    fun install(tree: JTree, project: Project, serverProvider: TomcatServerProvider, treeModel: TomcatTreeModel) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            private fun showPopup(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val server = node.userObject as? TomcatServer ?: return

                val group = DefaultActionGroup()
                if (server.status == "running") {
                    group.add(ActionManager.getInstance().getAction("dzide.StopServer"))
                    group.add(ActionManager.getInstance().getAction("dzide.RedeployWar"))
                } else {
                    group.add(ActionManager.getInstance().getAction("dzide.StartServer"))
                }
                group.addSeparator()
                group.add(ActionManager.getInstance().getAction("dzide.EditServer"))
                group.add(ActionManager.getInstance().getAction("dzide.RemoveServer"))

                val popupMenu: ActionPopupMenu = ActionManager.getInstance()
                    .createActionPopupMenu("DzideTomcatPopup", group)
                popupMenu.component.show(tree, e.x, e.y)
            }
        })
    }
}
