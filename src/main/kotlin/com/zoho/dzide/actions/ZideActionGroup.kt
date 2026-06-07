package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.zoho.dzide.ddmigration.SqlFileMigrationAction
import java.io.File

class ZideActionGroup : DefaultActionGroup() {

    override fun update(e: AnActionEvent) {
        val project: Project? = e.project
        val visible = project != null && isZideProject(project)
        e.presentation.isVisible = visible
        e.presentation.isEnabled = visible
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            BuildUpdateGroup(),
            HooksGroup(),
            DDMigrationGroup(),
            ReinitDbAction(),
            DeploymentPropertiesAction(),
            SetupGroup(),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        fun isZideProject(project: Project): Boolean {
            val basePath = project.basePath ?: return false
            return File(basePath, ".zide_resources").isDirectory
        }
    }
}

class BuildUpdateGroup : DefaultActionGroup("Update Deployment", true) {
    init {
        templatePresentation.description = "Deploy to server"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            CustomBuildAction(),
            UpdateDeploymentAction(),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class DDMigrationGroup : DefaultActionGroup("DD Migration", true) {
    init {
        templatePresentation.description = "Data Dictionary migration tools"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            SqlFileMigrationAction(),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class SetupGroup : DefaultActionGroup("Setup", true) {
    init {
        templatePresentation.description = "Project setup utilities"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            SetupDependenciesAction(),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class HooksGroup : DefaultActionGroup("Run Hooks", true) {
    init {
        templatePresentation.description = "Run ZIDE ANT hooks"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            RunHooksAction.RunAllHooks(),
            RunHooksAction.RunPrecreationHook(),
            RunHooksAction.RunPostcreationHook(),
            RunHooksAction.RunZideModuleHook(),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
