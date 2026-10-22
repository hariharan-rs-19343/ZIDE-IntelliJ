package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ShellUtil
import com.zoho.dzide.util.ZeroProxyManager
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class BuildAction : AnAction("Build", "Run ANT build script", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return

        val productName = Path.of(projectPath).name
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

        val exports = ShellUtil.buildExportChain(mapOf("ANT_HOME" to antHome) + ZeroProxyManager.processEnv())
        val antCommand = (exports + listOf("&&", antExe.toString())).joinToString(" ")

        TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(buildDir.toString(), "ZIDE Build: $productName", true)
            .executeCommand(antCommand)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
