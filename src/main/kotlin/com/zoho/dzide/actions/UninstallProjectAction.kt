package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import com.zoho.dzide.zide.ZideConfigParser
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

class UninstallProjectAction : AnAction("Uninstall Project", "Remove ZIDE project, deployment folder, and server configuration", null) {

    private val log = Logger.getInstance(UninstallProjectAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return

        val zideConfig = ZideConfigParser.readZideConfig(projectPath)
        val service = zideConfig?.service
        val serviceKey = service?.properties?.get("ZIDE.SERVICE_KEY") ?: service?.key ?: project.name
        val parentService = service?.properties?.get("ZIDE.PARENT_SERVICE") ?: project.name
        val deploymentFolder = service?.properties?.get("ZIDE.DEPLOYMENT_FOLDER")

        val dialog = UninstallConfirmDialog(project, serviceKey, parentService, deploymentFolder, projectPath)
        if (!dialog.showAndGet()) return

        val deleteProjectDir = dialog.isDeleteProjectChecked()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Uninstalling ZIDE Project: $parentService", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val serverProvider = TomcatServerProvider.getInstance(project)
                    val tomcatManager = TomcatManager.getInstance(project)

                    // Step 1: Stop running server
                    indicator.text = "Stopping server..."
                    indicator.fraction = 0.1
                    val matchingServer = serverProvider.getServers().find { it.zideServiceKey == serviceKey }
                    if (matchingServer != null && matchingServer.status == "running") {
                        ApplicationManager.getApplication().invokeAndWait {
                            tomcatManager.stopServer(matchingServer)
                        }
                        var waited = 0
                        while (PortUtil.isPortInUse(matchingServer.port) && waited < 15_000) {
                            Thread.sleep(1000)
                            waited += 1000
                        }
                    }

                    // Step 2: Remove Tomcat server configuration (must run on EDT -- triggers tree UI refresh)
                    indicator.text = "Removing server configuration..."
                    indicator.fraction = 0.3
                    if (matchingServer != null) {
                        ApplicationManager.getApplication().invokeAndWait {
                            serverProvider.removeServer(matchingServer.id)
                        }
                        log.info("Removed Tomcat server config: ${matchingServer.name}")
                    }

                    // Step 3: Delete deployment folder
                    indicator.text = "Deleting deployment folder..."
                    indicator.fraction = 0.5
                    if (deploymentFolder != null) {
                        val deployDir = File(deploymentFolder)
                        if (deployDir.exists()) {
                            val deleted = deployDir.deleteRecursively()
                            if (deleted) {
                                log.info("Deleted deployment folder: $deploymentFolder")
                            } else {
                                log.warn("Failed to fully delete deployment folder: $deploymentFolder")
                            }
                        }
                    }

                    if (deleteProjectDir) {
                        // Step 4: Close project and delete directory
                        indicator.text = "Closing project..."
                        indicator.fraction = 0.7

                        ApplicationManager.getApplication().invokeLater {
                            val projectDir = File(projectPath)
                            ProjectManager.getInstance().closeAndDispose(project)
                            if (projectDir.exists()) {
                                val deleted = projectDir.deleteRecursively()
                                if (deleted) {
                                    log.info("Deleted project directory: $projectPath")
                                } else {
                                    log.warn("Failed to fully delete project directory: $projectPath")
                                }
                            }
                        }
                    } else {
                        // Step 4b: Clean up .zide_resources content only
                        indicator.text = "Cleaning up ZIDE metadata..."
                        indicator.fraction = 0.7
                        val zideResources = File(projectPath, ".zide_resources")
                        val filesToRemove = listOf("service.xml", "zide_properties.xml", "repository.properties")
                        for (fileName in filesToRemove) {
                            val file = File(zideResources, fileName)
                            if (file.exists()) file.delete()
                        }
                        log.info("Cleaned up .zide_resources metadata for $parentService")

                        ApplicationManager.getApplication().invokeLater {
                            NotificationUtil.info(project, "ZIDE project '$parentService' uninstalled. Server config and deployment removed.")
                        }
                    }

                    indicator.fraction = 1.0
                    log.info("Uninstall completed for $parentService")

                } catch (ex: Exception) {
                    log.error("Uninstall failed for $parentService", ex)
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtil.error(project, "Uninstall failed: ${ex.message}")
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasProject = project != null && project.basePath != null
        e.presentation.isEnabledAndVisible = hasProject
    }

    private class UninstallConfirmDialog(
        project: com.intellij.openapi.project.Project,
        private val serviceKey: String,
        private val parentService: String,
        private val deploymentFolder: String?,
        private val projectPath: String
    ) : DialogWrapper(project, true) {

        private val deleteProjectCheckBox = JBCheckBox("Also delete project directory from disk", false)

        init {
            title = "Uninstall ZIDE Project"
            setOKButtonText("Uninstall")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(6, 8, 6, 8)
                gridx = 0
                weightx = 1.0
            }

            gbc.gridy = 0
            panel.add(JBLabel("<html><b>Uninstall $serviceKey ($parentService)?</b></html>"), gbc)

            gbc.gridy = 1
            gbc.insets = Insets(12, 8, 4, 8)
            panel.add(JBLabel("<html>This will remove:<ul>" +
                "<li>Tomcat server configuration for this service</li>" +
                "<li>Deployment folder: <code>${deploymentFolder ?: "N/A"}</code></li>" +
                "</ul></html>"), gbc)

            gbc.gridy = 2
            gbc.insets = Insets(4, 8, 4, 8)
            panel.add(JSeparator(), gbc)

            gbc.gridy = 3
            gbc.insets = Insets(4, 8, 4, 8)
            panel.add(deleteProjectCheckBox, gbc)

            gbc.gridy = 4
            gbc.insets = Insets(0, 28, 8, 8)
            panel.add(JBLabel("<html><small>Project directory: <code>$projectPath</code></small></html>"), gbc)

            gbc.gridy = 5
            gbc.insets = Insets(8, 8, 4, 8)
            panel.add(JBLabel("<html><small><b>Warning:</b> This action cannot be undone.</small></html>"), gbc)

            return panel
        }

        fun isDeleteProjectChecked(): Boolean = deleteProjectCheckBox.isSelected
    }
}
