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

            // Wait for the HTTP port (proves server is up) — do NOT probe the debug port
            // with a raw TCP socket, as JDWP interprets it as a debugger and kills the
            // listener on handshake failure.
            NotificationUtil.info(project, "Waiting for server to start before attaching debugger...")
            ApplicationManager.getApplication().executeOnPooledThread {
                val ready = PortUtil.waitForPort(server.port, 60000)
                if (!ready) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            NotificationUtil.error(project, "Server HTTP port ${server.port} did not become available. Server may have failed to start.")
                        }
                    }
                    return@executeOnPooledThread
                }

                // Give JPDA a moment to fully initialize after server is up
                Thread.sleep(2000)

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
                                    NotificationUtil.info(project, "Debugger attaching to ${server.name} on port $debugPort.")
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
