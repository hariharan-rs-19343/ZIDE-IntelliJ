package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.settings.ZideSettingsState
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProcessUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CustomBuildAction : AnAction("Custom Build", "Deploy from a remote build URL", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tomcatManager = TomcatManager.getInstance(project)

        val settings = ZideSettingsState.getInstance()
        val lastUrl = settings.customBuildUrl

        val url = Messages.showInputDialog(
            project,
            "Enter the build URL to download:",
            "Custom Build",
            null,
            lastUrl,
            null
        ) ?: return

        if (url.isBlank()) {
            NotificationUtil.warn(project, "Build URL cannot be empty.")
            return
        }

        settings.customBuildUrl = url.trim()

        val wgetrcFile = File(System.getProperty("user.home"), ".wgetrc")
        if (!wgetrcFile.exists()) {
            NotificationUtil.error(project, "~/.wgetrc not found. Configure credentials in Settings > Tools > Zide > Wget Configuration.")
            return
        }

        tomcatManager.ensureToolWindow {
            val console = tomcatManager.consoleView ?: return@ensureToolWindow

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
            val outputContent = toolWindow?.contentManager?.findContent("Output")
            if (outputContent != null) {
                toolWindow.contentManager.setSelectedContent(outputContent)
            }

            console.clear()
            ConsoleUtil.print(console, project, "=== Custom Build: Download & Deploy ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ConsoleUtil.print(console, project, "URL: ${url.trim()}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

            val trimmedUrl = url.trim()
            val fileName = trimmedUrl.substringAfterLast('/').ifEmpty { "build.zip" }

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading $fileName", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Downloading $fileName..."
                    var tempFile: Path? = null
                    try {
                        tempFile = Files.createTempFile("dzide-custom-build-", "-$fileName")

                        ConsoleUtil.print(console, project, "[Download] Downloading $fileName...\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        val wgetCommand = listOf(
                            "wget",
                            "--progress=dot:mega",
                            "-O", tempFile.toString(),
                            trimmedUrl
                        )

                        val handler = ProcessUtil.executeStreaming(
                            command = wgetCommand,
                            onStdout = { },
                            onStderr = { text ->
                                parseWgetProgress(text)?.let { percent ->
                                    indicator.isIndeterminate = false
                                    indicator.fraction = percent / 100.0
                                    indicator.text = "Downloading $fileName... ${percent.toInt()}%"
                                }
                            },
                            onExit = { }
                        )
                        handler.waitFor()

                        if (indicator.isCanceled) {
                            handler.destroyProcess()
                            Files.deleteIfExists(tempFile)
                            ConsoleUtil.print(console, project, "\n[Download] Cancelled by user.\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                            return
                        }

                        val exitCode = handler.process.exitValue()
                        if (exitCode != 0) {
                            ConsoleUtil.print(console, project, "\n[Download] FAILED (exit code $exitCode)\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "Download failed. Check URL and ~/.wgetrc credentials.")
                            Files.deleteIfExists(tempFile)
                            return
                        }

                        if (!tempFile.toFile().exists() || tempFile.toFile().length() == 0L) {
                            ConsoleUtil.print(console, project, "\n[Download] FAILED — file is empty or missing\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "Downloaded file is empty.")
                            Files.deleteIfExists(tempFile)
                            return
                        }

                        val sizeMb = tempFile.toFile().length() / 1024 / 1024
                        ConsoleUtil.print(console, project, "\n[Download] Complete ($sizeMb MB)\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        indicator.text = "Deploying $fileName..."
                        indicator.isIndeterminate = true
                        ConsoleUtil.print(console, project, "--- Proceeding with deployment ---\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        val server = resolveServer(project)
                        if (server != null) {
                            UpdateDeploymentAction.runDeploymentCore(project, server, tomcatManager, tempFile, console)
                        } else {
                            ConsoleUtil.print(console, project, "ERROR: Cannot resolve deployment folder. Check .zide_resources/service.xml\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }

                    } catch (ex: Exception) {
                        ConsoleUtil.print(console, project, "Error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        NotificationUtil.error(project, "Custom build failed: ${ex.message}")
                    } finally {
                        if (tempFile != null) {
                            Files.deleteIfExists(tempFile)
                        }
                    }
                }
            })
        }
    }

    private fun resolveServer(project: com.intellij.openapi.project.Project): com.zoho.dzide.model.TomcatServer? {
        val serverProvider = com.zoho.dzide.tomcat.TomcatServerProvider.getInstance(project)
        val servers = serverProvider.getServers()
        if (servers.isNotEmpty()) return servers.first()

        val projectPath = project.basePath ?: return null
        val zideConfig = com.zoho.dzide.zide.ZideConfigParser.readZideConfig(projectPath) ?: return null
        val deploymentFolder = zideConfig.service?.properties?.get("ZIDE.DEPLOYMENT_FOLDER") ?: return null
        val parentService = zideConfig.service?.properties?.get("ZIDE.PARENT_SERVICE") ?: ""
        val tomcatPath = "$deploymentFolder/AdventNet/Sas/tomcat"

        return com.zoho.dzide.model.TomcatServer(
            name = parentService.ifBlank { "ZIDE" },
            path = tomcatPath,
            zideRuntimeProperties = zideConfig.service?.properties
        )
    }

    private fun parseWgetProgress(text: String): Double? {
        val match = Regex("""(\d+)%""").find(text)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
