package com.zoho.dzide.runconfig

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.zoho.dzide.model.LaunchMode
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import com.zoho.dzide.util.ShellUtil

class TomcatRunState(
    environment: ExecutionEnvironment,
    private val config: TomcatRunConfiguration,
    private val launchMode: LaunchMode
) : CommandLineState(environment) {

    override fun startProcess(): OSProcessHandler {
        val project = environment.project
        val serverProvider = TomcatServerProvider.getInstance(project)
        val tomcatManager = TomcatManager.getInstance(project)
        val server = serverProvider.getServer(config.serverId)

        if (server == null) {
            NotificationUtil.error(project, "Server not found: ${config.serverId}")
            throw IllegalStateException("Server not found")
        }

        val contextPath = config.contextPath.ifBlank { "ROOT" }
        val warFilePath = config.warFilePath.ifBlank { null }

        if (warFilePath != null) {
            tomcatManager.deployWarFile(server, warFilePath, contextPath)
        }

        val script = ShellUtil.catalinaScript(server.path)
        val catalinaCommand = if (launchMode == LaunchMode.DEBUG) "jpda start" else "start"

        val envVars = mutableMapOf("CATALINA_PID" to "pid.file")
        if (launchMode == LaunchMode.DEBUG) {
            val debugPort = PortUtil.findAvailablePort(server.debugPort ?: 5005)
            envVars["JPDA_ADDRESS"] = "*:$debugPort"
            envVars["JPDA_TRANSPORT"] = "dt_socket"
            serverProvider.updateServer(server.id, mapOf("debugPort" to debugPort))
        }

        server.manualLaunchArgs?.let {
            if (it.isNotBlank()) envVars["CATALINA_OPTS"] = it
        }

        val commandLine = GeneralCommandLine("sh", script.toString(), *catalinaCommand.split(" ").toTypedArray())
            .withWorkDirectory(server.path)
        envVars.forEach { (k, v) -> commandLine.withEnvironment(k, v) }

        return OSProcessHandler(commandLine).also {
            it.startNotify()
        }
    }
}
