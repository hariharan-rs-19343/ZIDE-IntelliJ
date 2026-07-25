package com.zoho.dzide.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.util.NotificationUtil

/**
 * Attaches IntelliJ Remote debugger to a JPDA-suspended Tomcat process.
 * Shared by DebugOnServerAction and start-after-create.
 */
object DebuggerAttachUtil {

    fun attachAfterDelay(
        project: Project,
        server: TomcatServer,
        debugPort: Int,
        delayMs: Long = 3000
    ) {
        NotificationUtil.info(
            project,
            "Starting server in debug mode (suspend=y). Attaching debugger to port $debugPort..."
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@executeOnPooledThread
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                try {
                    attachNow(project, server, debugPort)
                } catch (ex: Exception) {
                    NotificationUtil.error(project, "Failed to attach debugger: ${ex.message}")
                }
            }
        }
    }

    fun attachNow(project: Project, server: TomcatServer, debugPort: Int) {
        val runManager = RunManager.getInstance(project)
        val remoteConfigType = ConfigurationTypeUtil.findConfigurationType("Remote")
            ?: throw IllegalStateException("Remote debug configuration type not found")
        val factory = remoteConfigType.configurationFactories.firstOrNull()
            ?: throw IllegalStateException("No Remote configuration factory")

        val settings = runManager.createConfiguration("Debug ${server.name}", factory)
        val remoteConfig = settings.configuration as? RemoteConfiguration
            ?: throw IllegalStateException("Failed to create RemoteConfiguration")

        remoteConfig.HOST = "localhost"
        remoteConfig.PORT = debugPort.toString()
        val modules = ModuleManager.getInstance(project).modules
        if (modules.isNotEmpty()) {
            remoteConfig.setModule(modules.first())
        }
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(
            settings,
            DefaultDebugExecutor.getDebugExecutorInstance()
        )
        NotificationUtil.info(project, "Debugger attached to ${server.name} on port $debugPort. Server resuming...")
    }
}
