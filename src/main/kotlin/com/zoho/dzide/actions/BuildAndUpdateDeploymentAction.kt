package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.deploysync.BuildOutputLocator
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.settings.CredentialResolver
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProcessUtil
import com.zoho.dzide.util.ProtocConfig
import com.zoho.dzide.util.ZeroProxyManager
import com.zoho.dzide.zide.ZideConfigParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Runs the product ANT build in `{project}/build/`, finds the produced zip ([BuildOutputLocator]),
 * then deploys it with the Update Deployment flow ([UpdateDeploymentAction.runDeploymentCore]).
 */
class BuildAndUpdateDeploymentAction : AnAction(
    "Build and Update Deployment",
    "Run ANT build, then deploy the produced zip",
    null
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return
        val tomcatManager = TomcatManager.getInstance(project)

        val buildDir = Path.of(projectPath, "build")
        if (!buildDir.exists() || !buildDir.isDirectory()) {
            NotificationUtil.error(project, "Build directory not found: $buildDir")
            return
        }

        val antHome = AntResolver.resolveAntHome(projectPath, null)
        if (antHome == null) {
            NotificationUtil.error(project, "ANT not found. Set ANT_HOME in ~/.zshrc or Settings > Tools > Zide")
            return
        }
        val antExe = AntResolver.resolveAntExecutable(antHome)

        val protocDir = BuildAction.resolveProtocDir(project)
        if (ProtocConfig.isEnabled() && protocDir == null) return

        // The build downloads its dependencies with the wget credential.
        CredentialResolver.requireWget(project) ?: return

        // Pick the server before the (long) build so nobody is asked afterwards.
        val server = ServerActionUtil.getSelectedServer(e)
            ?: UpdateDeploymentAction.resolveProjectServer(project)
            ?: return

        tomcatManager.ensureToolWindow {
            val console = tomcatManager.consoleView ?: return@ensureToolWindow

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
            val outputContent = toolWindow?.contentManager?.findContent("Output")
            if (outputContent != null) {
                toolWindow.contentManager.setSelectedContent(outputContent)
            }
            console.clear()

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Build and Update Deployment", true) {
                override fun run(indicator: ProgressIndicator) {
                    buildAndDeploy(project, server, tomcatManager, console, indicator, buildDir, antHome, antExe, protocDir)
                }
            })
        }
    }

    private fun buildAndDeploy(
        project: Project,
        server: TomcatServer,
        tomcatManager: TomcatManager,
        console: ConsoleView,
        indicator: ProgressIndicator,
        buildDir: Path,
        antHome: String,
        antExe: String,
        protocDir: String?
    ) {
        val startedAt = System.currentTimeMillis()
        val command = listOf(antExe) + (protocDir?.let { ProtocConfig.antArgs(it) } ?: emptyList())
        var env = mapOf("ANT_HOME" to antHome) + ZeroProxyManager.processEnv()
        if (protocDir != null) env = ProtocConfig.withPath(env, protocDir)

        ConsoleUtil.print(console, project, "=== Build and Update Deployment ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        if (protocDir != null) {
            ConsoleUtil.print(console, project, "Protoc: $protocDir\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        ConsoleUtil.print(console, project, "[Build] $ cd $buildDir && ${command.joinToString(" ")}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        indicator.isIndeterminate = true
        indicator.text = "Running ANT build..."
        val result = try {
            ProcessUtil.executeStreamingAndWait(
                command = command,
                workingDir = buildDir.toString(),
                env = env,
                timeoutMs = BUILD_TIMEOUT_MS,
                shouldCancel = { indicator.isCanceled },
                onStdout = { ConsoleUtil.print(console, project, it, ConsoleViewContentType.NORMAL_OUTPUT) },
                onStderr = { ConsoleUtil.print(console, project, it, ConsoleViewContentType.ERROR_OUTPUT) }
            )
        } catch (ex: Exception) {
            ConsoleUtil.print(console, project, "\n[Build] FAILED: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            NotificationUtil.error(project, "ANT build failed: ${ex.message}")
            return
        }

        if (indicator.isCanceled) {
            ConsoleUtil.print(console, project, "\n[Build] Cancelled by user.\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            return
        }
        if (result.exitCode != 0) {
            ConsoleUtil.print(console, project, "\n[Build] FAILED (exit code ${result.exitCode}). Deployment skipped.\n", ConsoleViewContentType.ERROR_OUTPUT)
            NotificationUtil.error(project, "ANT build failed (exit code ${result.exitCode}). Deployment skipped.")
            return
        }
        ConsoleUtil.print(console, project, "\n[Build] SUCCESS\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        val output = locateZip(project, buildDir, startedAt)
        if (output == null) {
            ConsoleUtil.print(console, project, "Deployment skipped (no zip selected).\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            return
        }
        ConsoleUtil.print(console, project, "Build output: ${output.first} (from ${output.second})\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        indicator.text = "Updating deployment..."
        UpdateDeploymentAction.runDeploymentCore(project, server, tomcatManager, output.first, console)
    }

    /** Auto-detect the produced zip; otherwise ask once and remember the pick for this project. */
    private fun locateZip(project: Project, buildDir: Path, startedAt: Long): Pair<Path, String>? {
        val properties = PropertiesComponent.getInstance(project)
        val downloadUrl = project.basePath
            ?.let { ZideConfigParser.readZideConfig(it) }
            ?.service?.properties?.get("ZIDE.DOWNLOAD_URL")

        BuildOutputLocator.find(buildDir, downloadUrl, properties.getValue(KEY_OUTPUT_ZIP), startedAt)?.let { return it }

        val startDir = BuildOutputLocator.outputDir(buildDir)?.takeIf { it.isDirectory() } ?: buildDir
        var picked: Path? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
                .withTitle("Select Build Output Zip")
                .withDescription("The build zip was not found automatically. Choose it; it will be used for future builds.")
                .withFileFilter { it.extension == "zip" }
            val initial = LocalFileSystem.getInstance().refreshAndFindFileByPath(startDir.toString())
            picked = FileChooserFactory.getInstance()
                .createFileChooser(descriptor, project, null)
                .choose(project, *listOfNotNull(initial).toTypedArray())
                .firstOrNull()
                ?.let { Path.of(it.path) }
        }
        val zip = picked?.takeIf { it.extension.equals("zip", ignoreCase = true) } ?: return null
        properties.setValue(KEY_OUTPUT_ZIP, zip.toString())
        return zip to "selected file (saved for future builds)"
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    companion object {
        private const val KEY_OUTPUT_ZIP = "dzide.buildAndUpdate.outputZip"
        private const val BUILD_TIMEOUT_MS = 60L * 60 * 1000
    }
}
