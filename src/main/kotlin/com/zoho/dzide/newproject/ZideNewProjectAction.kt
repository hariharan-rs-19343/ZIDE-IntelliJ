package com.zoho.dzide.newproject

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class ZideNewProjectAction : AnAction("New Project", "Create a new ZIDE project", null) {

    private val log = Logger.getInstance(ZideNewProjectAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        if (!ZideProjectCreator.ensureCmToolToken()) return

        val dialog = ZideProjectWizardDialog(project)
        if (!dialog.showAndGet()) return

        val result = dialog.getResult()
        if (result.name.isBlank()) {
            Messages.showErrorDialog("Project name cannot be empty.", "New ZIDE Project")
            return
        }

        val creator = ZideProjectCreator(result)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating ZIDE Project: ${result.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                creator.create(indicator)
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
