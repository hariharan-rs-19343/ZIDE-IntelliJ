package com.zoho.dzide.util

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

object ConsoleUtil {

    fun print(console: ConsoleView, project: Project, text: String, type: ConsoleViewContentType) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                console.print(text, type)
            }
        }
    }
}
