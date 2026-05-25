package com.zoho.dzide.deploysync

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.parser.ModuleZidePropsParser
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.tomcat.TomcatServerProvider
import com.zoho.dzide.util.ProcessUtil
import com.zoho.dzide.util.ShellUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension

@Service(Service.Level.PROJECT)
class ResourceSyncManager(private val project: Project) : Disposable {

    var consoleView: ConsoleView? = null

    private val lastExecutionByPath = ConcurrentHashMap<String, Long>()

    private fun log(message: String) {
        val timestamped = "[${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}] $message\n"
        consoleView?.print(timestamped, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun shouldDebounce(filePath: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastExecutionByPath.put(filePath, now) ?: 0
        return now - previous < 300
    }

    private fun getServerForProject(projectPath: String, filePath: String): TomcatServer? {
        val serverProvider = TomcatServerProvider.getInstance(project)
        val mappings = serverProvider.getMappings()
        val matched = mappings
            .filter { PathResolver.isSubPath(it.projectPath, filePath) || PathResolver.isSubPath(projectPath, it.projectPath) }
            .sortedByDescending { it.projectPath.length }
            .firstOrNull() ?: return null
        return serverProvider.getServer(matched.serverId)
    }

    private fun isPathWithinFolder(relativePath: String, folder: String): Boolean =
        relativePath == folder || relativePath.startsWith("$folder/")

    private fun refreshServerFromZideProperties(server: TomcatServer): TomcatServer {
        if (server.zidePropertiesPath == null || !Path.of(server.zidePropertiesPath!!).exists()) return server

        val parsed = ModuleZidePropsParser.readModuleZidePropsDataFromFile(server.zidePropertiesPath)
        val updates = mutableMapOf<String, Any?>()

        if (parsed.launchVmArguments != null && parsed.launchVmArguments != server.zideLaunchVmArguments) {
            updates["zideLaunchVmArguments"] = parsed.launchVmArguments
        }
        if (parsed.hookTasksRaw != null && parsed.hookTasksRaw != server.zideHookTasksRaw) {
            updates["zideHookTasksRaw"] = parsed.hookTasksRaw
        }
        if (parsed.autoResourceCopyRaw != null && parsed.autoResourceCopyRaw != server.zideAutoResourceCopyRaw) {
            updates["zideAutoResourceCopyRaw"] = parsed.autoResourceCopyRaw
        }

        if (updates.isNotEmpty()) {
            TomcatServerProvider.getInstance(project).updateServer(server.id, updates)
            return server.copy(
                zideLaunchVmArguments = updates["zideLaunchVmArguments"] as? String ?: server.zideLaunchVmArguments,
                zideHookTasksRaw = updates["zideHookTasksRaw"] as? String ?: server.zideHookTasksRaw,
                zideAutoResourceCopyRaw = updates["zideAutoResourceCopyRaw"] as? String ?: server.zideAutoResourceCopyRaw
            )
        }
        return server
    }

    private fun buildAntRuntimeArgs(server: TomcatServer): String {
        if (server.zideRuntimeProperties == null) return ""
        return server.zideRuntimeProperties!!.entries
            .joinToString(" ") { "-D${it.key}=${ShellUtil.shellEscapeSingleQuoted(it.value)}" }
    }

    private fun runAntTarget(
        projectRoot: String,
        server: TomcatServer,
        target: String,
        deltaResourcesPath: String,
        deltaResources: String
    ) {
        val antHome = AntResolver.resolveAntHome(projectRoot, server.antHomeResolvedPath)
        if (antHome == null) {
            log("ANT home not resolved. Skipping target '$target'.")
            return
        }

        if (antHome != server.antHomeResolvedPath) {
            TomcatServerProvider.getInstance(project).updateServer(server.id, mapOf("antHomeResolvedPath" to antHome))
        }

        val antExecutable = AntResolver.resolveAntExecutable(antHome)
        val buildXml = server.zideBuildXmlPath ?: Path.of(projectRoot, ".zide_resources", "zide_build", "build.xml").toString()
        val buildBaseDir = server.zideBuildBaseDir ?: Path.of(projectRoot, ".zide_resources", "zide_build").toString()

        if (!Path.of(buildXml).exists()) {
            log("build.xml not found at $buildXml. Skipping target '$target'.")
            return
        }

        val runtimeArgs = buildAntRuntimeArgs(server)
        val command = ShellUtil.buildShellCommand(
            "\"$antExecutable\"",
            "-f", "\"$buildXml\"",
            "-Dbasedir=\"$buildBaseDir\"",
            runtimeArgs,
            "-DREPOSITORY_PATH=$projectRoot",
            "-DDEPLOYMENT_PATH=${server.path}",
            "-DZIDE.DO_REPLACE=true",
            "-DDELTA_RESOURCES_PATH=${ShellUtil.shellEscapeSingleQuoted(deltaResourcesPath)}",
            "-DDELTA_RESOURCES=${ShellUtil.shellEscapeSingleQuoted(deltaResources)}",
            "-Dtarget=$target"
        )

        log("Running ANT target '$target' for project ${Path.of(projectRoot).fileName}.")
        try {
            ProcessUtil.executeCapturing(command, projectRoot)
        } catch (e: Exception) {
            log("ANT target '$target' failed: ${e.message}")
        }
    }

    private fun runAutoCopyForFile(projectRoot: String, server: TomcatServer, filePath: String, projectName: String) {
        val mappings = ModuleZidePropsParser.parseAutoCopyMappings(server.zideAutoResourceCopyRaw, projectName)
        for (mapping in mappings) {
            val sourceRoot = Path.of(projectRoot, mapping.sourcePath).toString()
            if (!PathResolver.isSubPath(sourceRoot, filePath)) continue

            val subPath = Path.of(sourceRoot).relativize(Path.of(filePath)).toString()
            val destinationRoot = Path.of(
                server.path,
                PathResolver.applyProjectNamePlaceholder(mapping.destinationPathTemplate, projectName)
            )
            val destinationPath = destinationRoot.resolve(subPath)

            try {
                Files.createDirectories(destinationPath.parent)
                Files.copy(Path.of(filePath), destinationPath, StandardCopyOption.REPLACE_EXISTING)
                log("Copied resource: $filePath -> $destinationPath")
            } catch (e: Exception) {
                log("Resource copy failed for $filePath: ${e.message}")
            }
        }
    }


    private fun triggerHotSwap() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val debuggerManager = com.intellij.debugger.DebuggerManagerEx.getInstanceEx(project)
            val sessions = debuggerManager.sessions
            if (sessions.isEmpty()) {
                log("No active debug session — hot-swap skipped. Changes will take effect after restart.")
                return@invokeLater
            }
            for (session in sessions) {
                val hotSwapManager = com.intellij.debugger.ui.HotSwapUI.getInstance(project)
                hotSwapManager.reloadChangedClasses(session, false)
                log("Hot-swap triggered for debug session: ${session.sessionName}")
            }
        }
    }

    fun handleDocumentSave(filePath: String) {
        if (shouldDebounce(filePath)) return

        val projectRoot = PathResolver.findNearestProjectRoot(filePath) ?: return
        val projectDirectoryName = Path.of(projectRoot).fileName.toString()
        val server = getServerForProject(projectRoot, filePath) ?: return
        val refreshedServer = refreshServerFromZideProperties(server)
        val m19ProjectName = refreshedServer.repositoryModuleDir ?: projectDirectoryName
        if (filePath.endsWith(".java")) {
            // Compiler output is set directly to deployment WEB-INF/classes/,
            // so IntelliJ's auto-build writes .class files there on save.
            // We only need to trigger JDWP hot-swap if a debug session is active.
            triggerHotSwap()
            return
        }

        val relativePath = PathResolver.stripProjectPrefix(
            PathResolver.toProjectRelativePath(projectRoot, filePath), m19ProjectName
        )
        val hookMappings = ModuleZidePropsParser.parseHookTaskMappings(refreshedServer.zideHookTasksRaw, m19ProjectName)
        for (mapping in hookMappings) {
            if (!isPathWithinFolder(relativePath, mapping.folder)) continue
            val deltaResourcesPath = PathResolver.normalizePathSlashes("$projectDirectoryName/${mapping.folder}")
            val deltaResources = PathResolver.normalizePathSlashes(
                Path.of(mapping.folder).relativize(Path.of(relativePath)).toString()
            ).ifEmpty { "null" }
            runAntTarget(projectRoot, refreshedServer, mapping.antTarget, deltaResourcesPath, deltaResources)
        }

        runAutoCopyForFile(projectRoot, refreshedServer, filePath, projectDirectoryName)
    }

    override fun dispose() {
        // Clean up resources
    }

    companion object {
        fun getInstance(project: Project): ResourceSyncManager =
            project.getService(ResourceSyncManager::class.java)
    }
}
