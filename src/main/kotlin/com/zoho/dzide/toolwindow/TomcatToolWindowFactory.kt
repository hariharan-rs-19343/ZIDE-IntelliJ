package com.zoho.dzide.toolwindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import javax.swing.JPanel
import javax.swing.JScrollPane
import java.awt.BorderLayout

class TomcatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val serverProvider = TomcatServerProvider.getInstance(project)
        val tomcatManager = TomcatManager.getInstance(project)

        // Create tree panel
        val treeModel = TomcatTreeModel(serverProvider)
        val tree = Tree(treeModel)
        tree.cellRenderer = TomcatTreeCellRenderer()
        tree.isRootVisible = false
        tree.showsRootHandles = true

        // Set up context menu
        TomcatTreePopupHandler.install(tree, project, serverProvider, treeModel)

        val treePanel = JPanel(BorderLayout())
        treePanel.add(JScrollPane(tree), BorderLayout.CENTER)

        // Add toolbar
        val toolbar = TomcatToolbar.create(project, serverProvider, treeModel)
        treePanel.add(toolbar.component, BorderLayout.NORTH)

        val contentFactory = ContentFactory.getInstance()
        val treeContent = contentFactory.createContent(treePanel, "Servers", false)
        treeContent.isCloseable = false
        toolWindow.contentManager.addContent(treeContent)

        // Create console panel with toolbar (includes Clear button)
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        tomcatManager.consoleView = console

        val consolePanel = JPanel(BorderLayout())
        consolePanel.add(console.component, BorderLayout.CENTER)
        try {
            val consoleActions = DefaultActionGroup(*console.createConsoleActions())
            val consoleToolbar = ActionManager.getInstance().createActionToolbar("OutputConsole", consoleActions, false)
            consoleToolbar.targetComponent = console.component
            consolePanel.add(consoleToolbar.component, BorderLayout.WEST)
        } catch (_: Exception) { }

        val consoleContent = contentFactory.createContent(consolePanel, "Output", false)
        consoleContent.isCloseable = false
        toolWindow.contentManager.addContent(consoleContent)

        // Create App Logs console panel with toolbar (includes Clear button)
        val appLogsConsole: ConsoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        tomcatManager.appLogsConsoleView = appLogsConsole

        val appLogsPanel = JPanel(BorderLayout())
        appLogsPanel.add(appLogsConsole.component, BorderLayout.CENTER)
        try {
            val appLogsActions = DefaultActionGroup()
            val refreshAppLogs = ActionManager.getInstance().getAction("dzide.RefreshAppLogs")
            if (refreshAppLogs != null) appLogsActions.add(refreshAppLogs)
            appLogsActions.addSeparator()
            appLogsActions.addAll(*appLogsConsole.createConsoleActions())
            val appLogsToolbar = ActionManager.getInstance().createActionToolbar("AppLogsConsole", appLogsActions, false)
            appLogsToolbar.targetComponent = appLogsConsole.component
            appLogsPanel.add(appLogsToolbar.component, BorderLayout.WEST)
        } catch (_: Exception) { }

        val appLogsContent = contentFactory.createContent(appLogsPanel, "App Logs", false)
        appLogsContent.isCloseable = false
        toolWindow.contentManager.addContent(appLogsContent)

        // Listen for changes — must run on EDT since tree model updates Swing UI
        serverProvider.addChangeListener {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                treeModel.reload()
            }
        }
    }
}
