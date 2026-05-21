package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProcessUtil
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class BuildAction : AnAction("Build", "Run ANT build script", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return
        val tomcatManager = TomcatManager.getInstance(project)

        tomcatManager.ensureToolWindow {
            val console = tomcatManager.consoleView ?: return@ensureToolWindow

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
            val outputContent = toolWindow?.contentManager?.findContent("Output")
            if (outputContent != null) {
                toolWindow.contentManager.setSelectedContent(outputContent)
            }

            val productName = Path.of(projectPath).name
            val buildDir = Path.of(projectPath, "build")

            if (!buildDir.exists() || !buildDir.isDirectory()) {
                NotificationUtil.error(project, "Build directory not found: $buildDir")
                return@ensureToolWindow
            }

            val antHome = AntResolver.resolveAntHome(projectPath, null)
            if (antHome == null) {
                NotificationUtil.error(project, "ANT not found. Set ANT_HOME in ~/.zshrc or Settings > Tools > Zide")
                return@ensureToolWindow
            }
            val antExe = AntResolver.resolveAntExecutable(antHome)

            console.clear()
            console.print("=== DZIDE Build: $productName ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print("Build dir: $buildDir\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print("ANT: $antExe\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val handler = ProcessUtil.executeStreaming(
                        command = listOf(antExe),
                        workingDir = buildDir.toString(),
                        env = mapOf("ANT_HOME" to antHome),
                        onStdout = { text ->
                            ConsoleUtil.print(console, project, text, ConsoleViewContentType.NORMAL_OUTPUT)
                        },
                        onStderr = { text ->
                            ConsoleUtil.print(console, project, text, ConsoleViewContentType.ERROR_OUTPUT)
                        },
                        onExit = { exitCode ->
                            if (exitCode != 0) {
                                ConsoleUtil.print(console, project, "\nBuild FAILED (exit code $exitCode)\n", ConsoleViewContentType.ERROR_OUTPUT)
                                NotificationUtil.error(project, "ANT build failed with exit code $exitCode")
                            } else {
                                ConsoleUtil.print(console, project, "\n=== Build complete ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                NotificationUtil.info(project, "Build completed: $productName")
                            }
                        }
                    )
                    handler.waitFor()
                } catch (ex: Exception) {
                    ConsoleUtil.print(console, project, "Error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    NotificationUtil.error(project, "Build failed: ${ex.message}")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
