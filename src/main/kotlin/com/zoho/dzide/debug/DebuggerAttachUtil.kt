package com.zoho.dzide.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProcessUtil

/**
 * Attaches IntelliJ Remote debugger to a JPDA-suspended Tomcat process.
 * Shared by DebugOnServerAction and start-after-create.
 */
object DebuggerAttachUtil {

    /**
     * Polls until the JPDA agent has bound [debugPort] (via lsof — no TCP handshake),
     * then attaches. Avoids "Connection refused" when pre-start setup takes longer than
     * a fixed delay.
     */
    fun attachAfterDelay(
        project: Project,
        server: TomcatServer,
        debugPort: Int,
        timeoutMs: Long = 60_000
    ) {
        NotificationUtil.info(
            project,
            "Waiting for JPDA agent on port $debugPort before attaching debugger..."
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val start = System.currentTimeMillis()
            var portReady = false
            try {
                Thread.sleep(1000) // brief pause before first poll
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@executeOnPooledThread
            }

            while (System.currentTimeMillis() - start < timeoutMs) {
                if (project.isDisposed) return@executeOnPooledThread
                try {
                    val result = ProcessUtil.executeCapturing(
                        command = listOf("lsof", "-ti", ":$debugPort"),
                        timeoutMs = 3000
                    )
                    if (result.stdout.trim().isNotBlank()) {
                        portReady = true
                        break
                    }
                } catch (_: Exception) {
                    // lsof missing or failed — keep polling until timeout
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@executeOnPooledThread
                }
            }

            if (!portReady) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        NotificationUtil.error(
                            project,
                            "Timed out waiting for debug port $debugPort. Server may have failed to start."
                        )
                    }
                }
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
            // Prefer the module whose compiler output was redirected to WEB-INF/classes
            // (multi-module projects like ZhareHub). Fall back to first module.
            val targetModule = modules.firstOrNull { m ->
                ModuleRootManager.getInstance(m)
                    .getModuleExtension(CompilerModuleExtension::class.java)
                    ?.compilerOutputUrl
                    ?.contains("WEB-INF/classes") == true
            } ?: modules.first()
            remoteConfig.setModule(targetModule)
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
