package com.zoho.dzide.update

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.zoho.dzide.util.NotificationUtil

class CheckForUpdateAction : AnAction("Check for Updates", "Check if a newer version of ZIDE is available", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        ApplicationManager.getApplication().executeOnPooledThread {
            val updateInfo = PluginUpdateChecker.checkForUpdate()

            ApplicationManager.getApplication().invokeLater {
                if (updateInfo != null) {
                    PluginUpdateChecker.showUpdateNotification(project, updateInfo)
                } else {
                    NotificationUtil.info(project, "ZIDE is up to date (v${PluginUpdateChecker.getCurrentVersion()}).")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
