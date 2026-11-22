package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.settings.ZideSettingsConfigurable
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProtocConfig
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

        val protocDir = resolveProtocDir(project)
        if (ProtocConfig.isEnabled() && protocDir == null) return

        val exports = ShellUtil.buildExportChain(mapOf("ANT_HOME" to antHome) + ZeroProxyManager.processEnv()).toMutableList()
        val antParts = mutableListOf(antExe)
        if (protocDir != null) {
            // Built by hand: buildExportChain single-quotes values, which would stop $PATH expanding.
            exports += listOf("&&", "export PATH=${ShellUtil.shellEscapeSingleQuoted(protocDir)}:\"\$PATH\"")
            antParts += ProtocConfig.antArgs(protocDir).map { ShellUtil.shellEscapeSingleQuoted(it) }
        }
        val antCommand = (exports + "&&" + antParts).joinToString(" ")

        TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(buildDir.toString(), "ZIDE Build: $productName", true)
            .executeCommand(antCommand)
    }

    companion object {
        /**
         * Protoc folder for ANT when "Use custom protoc" is on. Null when the setting is off, or when it is on
         * but the path is no longer valid — then an error with an Open Settings link is shown.
         */
        fun resolveProtocDir(project: Project): String? {
            if (!ProtocConfig.isEnabled()) return null
            val dir = ProtocConfig.activeBinDir()
            if (dir == null) {
                NotificationUtil.error(
                    project,
                    "Custom protoc is enabled but the protoc path is missing or invalid. " +
                        "Set it in Settings > Tools > Zide.",
                    NotificationUtil.openSettingsAction(ZideSettingsConfigurable::class.java)
                )
            }
            return dir
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
