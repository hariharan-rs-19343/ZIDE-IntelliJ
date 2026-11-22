package com.zoho.dzide.util

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

object NotificationUtil {

    private const val GROUP_ID = "DZIDE Notifications"

    fun info(project: Project?, content: String) {
        notify(project, content, NotificationType.INFORMATION)
    }

    fun warn(project: Project?, content: String, action: NotificationAction? = null) {
        notify(project, content, NotificationType.WARNING, action)
    }

    fun error(project: Project?, content: String, action: NotificationAction? = null) {
        notify(project, content, NotificationType.ERROR, action)
    }

    /** "Open Settings" link that jumps to the given settings page. */
    fun openSettingsAction(page: Class<out Configurable>): NotificationAction =
        NotificationAction.createSimpleExpiring("Open Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(null, page)
        }

    private fun notify(project: Project?, content: String, type: NotificationType, action: NotificationAction? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        action?.let { notification.addAction(it) }
        notification.notify(project)
    }
}
