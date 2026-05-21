package com.zoho.dzide.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.zoho.dzide.tomcat.TomcatServerProvider

object TomcatToolbar {

    @Suppress("UNUSED_PARAMETER")
    fun create(project: Project, serverProvider: TomcatServerProvider, treeModel: TomcatTreeModel): ActionToolbar {
        val am = ActionManager.getInstance()
        val group = DefaultActionGroup()
        group.add(am.getAction("dzide.AddServer"))
        group.add(am.getAction("dzide.RemoveServer"))
        group.add(am.getAction("dzide.RefreshServers"))
        group.addSeparator()
        group.add(am.getAction("dzide.StopServer"))
        group.add(am.getAction("dzide.RestartServer"))
        val toolbar = am.createActionToolbar("DzideTomcatToolbar", group, true)
        toolbar.targetComponent = null
        return toolbar
    }
}
