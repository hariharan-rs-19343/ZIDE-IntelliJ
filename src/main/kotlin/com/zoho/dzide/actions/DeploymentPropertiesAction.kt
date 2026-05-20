package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import com.zoho.dzide.zide.DeploymentPropertiesDialog
import com.zoho.dzide.zide.ZideConfigParser

class DeploymentPropertiesAction : AnAction("Deployment Properties", "Edit ZIDE deployment properties", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: return

        val projectPath = project.basePath ?: return
        val zideConfig = ZideConfigParser.readZideConfig(projectPath)
        if (zideConfig == null) {
            NotificationUtil.error(project, "ZIDE configuration not found. Ensure .zide_resources/ exists with service.xml and zide_properties.xml.")
            return
        }

        val properties = zideConfig.properties ?: run {
            NotificationUtil.error(project, "Could not read zide_properties.xml.")
            return
        }

        val serviceKey = properties.serviceKey
        val serverRunning = PortUtil.isPortInUse(server.port)
        val dialog = DeploymentPropertiesDialog(project, serviceKey, properties.properties, readOnly = serverRunning)

        if (dialog.showAndGet() && !serverRunning) {
            val updated = dialog.getUpdatedProperties()
            val changed = updated.filter { (key, value) -> properties.properties[key] != value }
            if (changed.isEmpty()) {
                NotificationUtil.info(project, "No changes to deployment properties.")
                return
            }

            ZideConfigParser.writePropertiesToXml(projectPath, serviceKey, changed)
            NotificationUtil.info(project, "Deployment properties updated successfully.")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
