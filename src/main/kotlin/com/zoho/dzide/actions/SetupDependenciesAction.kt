package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.zoho.dzide.dependency.DependencyLinker
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.zide.ZideConfigParser

class SetupDependenciesAction : AnAction("Setup Dependencies", "Link deployment JARs to project module", null) {

    private val log = Logger.getInstance(SetupDependenciesAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return

        DumbService.getInstance(project).runWhenSmart {
            try {
                val zideConfig = ZideConfigParser.readZideConfig(projectPath)
                val props = zideConfig?.service?.properties ?: run {
                    NotificationUtil.error(project, "Cannot read ZIDE configuration.")
                    return@runWhenSmart
                }

                val deploymentFolder = props["ZIDE.DEPLOYMENT_FOLDER"] ?: run {
                    NotificationUtil.error(project, "Deployment folder not configured in service.xml")
                    return@runWhenSmart
                }

                val parentService = props["ZIDE.PARENT_SERVICE"] ?: project.name
                val webappDir = PathResolver.resolveWebappDirectory(
                    "$deploymentFolder/AdventNet/Sas/tomcat",
                    parentService
                )

                if (webappDir == null) {
                    NotificationUtil.error(project, "Cannot find webapp directory in deployment.")
                    return@runWhenSmart
                }

                val modules = ModuleManager.getInstance(project).modules
                if (modules.isEmpty()) {
                    NotificationUtil.error(project, "No modules found in project.")
                    return@runWhenSmart
                }

                val linker = DependencyLinker(project)
                linker.linkDeploymentLibraries(webappDir, modules.first())
                NotificationUtil.info(project, "Dependencies linked successfully.")
                log.info("[DependencyLinker] Dependencies linked for ${project.name}")
            } catch (ex: Exception) {
                log.error("[DependencyLinker] Failed to link dependencies", ex)
                NotificationUtil.error(project, "Failed to link dependencies: ${ex.message}")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }
}
