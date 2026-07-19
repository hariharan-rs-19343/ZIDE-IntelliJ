package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil

class DebugOnServerAction : AnAction("Debug", "Start server in debug mode and attach debugger", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: run {
            NotificationUtil.error(project, "No Tomcat server configured. Add a server first via ZIDE > Add Tomcat Server.")
            return
        }
        val tomcatManager = TomcatManager.getInstance(project)

        val debugPort = server.debugPort?.takeIf { it > 0 }
            ?: PortUtil.findAvailablePort(8000)

        try {
            tomcatManager.startServerInDebug(server, debugPort)

            // With JPDA_SUSPEND=y, the JVM halts immediately after opening the JPDA socket
            // and waits for a debugger to connect. We wait a short delay for the socket to
            // initialize, then attach the debugger. Once attached, the server resumes and
            // startup breakpoints will be hit.
            NotificationUtil.info(project, "Starting server in debug mode (suspend=y). Attaching debugger to port $debugPort...")
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(3000)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    try {
                        val runManager = com.intellij.execution.RunManager.getInstance(project)
                        val remoteConfigType = com.intellij.execution.configurations.ConfigurationTypeUtil
                            .findConfigurationType("Remote")
                        if (remoteConfigType != null) {
                            val factory = remoteConfigType.configurationFactories.firstOrNull()
                            if (factory != null) {
                                val settings = runManager.createConfiguration(
                                    "Debug ${server.name}", factory
                                )
                                val remoteConfig = settings.configuration as? com.intellij.execution.remote.RemoteConfiguration
                                if (remoteConfig != null) {
                                    remoteConfig.HOST = "localhost"
                                    remoteConfig.PORT = debugPort.toString()
                                    val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
                                    val modules = moduleManager.modules
                                    if (modules.isNotEmpty()) {
                                        remoteConfig.setModule(modules.first())
                                    }
                                    settings.isTemporary = true
                                    runManager.addConfiguration(settings)
                                    runManager.selectedConfiguration = settings
                                    com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
                                        settings,
                                        com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance()
                                    )
                                    NotificationUtil.info(project, "Debugger attached to ${server.name} on port $debugPort. Server resuming...")
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        NotificationUtil.error(project, "Failed to attach debugger: ${ex.message}")
                    }
                }
            }
        } catch (ex: Exception) {
            NotificationUtil.error(project, "Debug failed: ${ex.message}")
        }
    }
}
