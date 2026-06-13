package com.zoho.dzide.deploysync

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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

    private val ideaLog = Logger.getInstance(ResourceSyncManager::class.java)

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

    private fun resolveProjectDirMacro(path: String): String {
        val basePath = project.basePath ?: return path
        return path
            .replace("\$PROJECT_DIR\$", basePath)
            .replace("${'$'}PROJECT_DIR${'$'}", basePath)
    }

    private fun resolveServerMacros(server: TomcatServer): TomcatServer {
        return server.copy(
            path = resolveProjectDirMacro(server.path),
            zideFolderPath = server.zideFolderPath?.let { resolveProjectDirMacro(it) },
            zidePropertiesPath = server.zidePropertiesPath?.let { resolveProjectDirMacro(it) },
            zideBuildXmlPath = server.zideBuildXmlPath?.let { resolveProjectDirMacro(it) },
            zideBuildBaseDir = server.zideBuildBaseDir?.let { resolveProjectDirMacro(it) }
        )
    }

    /**
     * Resolves the server for this project using a simple, robust approach:
     * 1. Try the project mapping by basePath
     * 2. Fallback: return the first server in this project's state
     *
     * No file-path matching needed — the project identity is already
     * resolved by DeploySyncSaveListener before calling handleDocumentSave().
     */
    private fun getServerForProject(): TomcatServer? {
        val serverProvider = TomcatServerProvider.getInstance(project)
        val basePath = project.basePath

        if (basePath != null) {
            val mapping = serverProvider.getProjectMapping(basePath)
            if (mapping != null) {
                val server = serverProvider.getServer(mapping.serverId)
                if (server != null) {
                    ideaLog.debug("Deploy sync: resolved server '${server.name}' via project mapping (basePath=$basePath)")
                    return server
                }
            }
        }

        val servers = serverProvider.getServers()
        if (servers.isNotEmpty()) {
            val server = servers.first()
            ideaLog.debug("Deploy sync: resolved server '${server.name}' via fallback (first of ${servers.size} servers)")
            return server
        }

        ideaLog.warn("Deploy sync: no servers configured. basePath=$basePath, mappings=${serverProvider.getMappings().map { "projectPath='${it.projectPath}' serverId='${it.serverId}'" }}")
        return null
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
        var copied = false
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
                copied = true
            } catch (e: Exception) {
                log("Resource copy failed for $filePath: ${e.message}")
            }
        }
        if (!copied && mappings.isNotEmpty()) {
            log("No auto-copy mapping matched for: ${Path.of(filePath).fileName}")
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

        val projectRoot = project.basePath
        if (projectRoot == null) {
            ideaLog.debug("Deploy sync: project.basePath is null for ${project.name}")
            return
        }

        val server = getServerForProject()
        if (server == null) {
            log("Deploy sync skipped: no server configured for this project.")
            return
        }

        val resolved = resolveServerMacros(server)
        val refreshed = refreshServerFromZideProperties(resolved)
        val projectDirectoryName = Path.of(projectRoot).fileName.toString()
        val m19ProjectName = refreshed.repositoryModuleDir ?: projectDirectoryName

        val fileExtension = Path.of(filePath).extension
        if (fileExtension == "java") {
            triggerHotSwap()
            return
        }

        log("Deploy sync triggered for .$fileExtension file: ${Path.of(filePath).fileName}")
        ideaLog.info("Deploy sync: file=${Path.of(filePath).fileName}, server=${refreshed.name}, tomcatPath=${refreshed.path}, autoCopy=${refreshed.zideAutoResourceCopyRaw}, hooks=${refreshed.zideHookTasksRaw}")

        val relativePath = PathResolver.stripProjectPrefix(
            PathResolver.toProjectRelativePath(projectRoot, filePath), m19ProjectName
        )
        val hookMappings = ModuleZidePropsParser.parseHookTaskMappings(refreshed.zideHookTasksRaw, m19ProjectName)
        var hookMatched = false
        for (mapping in hookMappings) {
            if (!isPathWithinFolder(relativePath, mapping.folder)) continue
            hookMatched = true
            val deltaResourcesPath = PathResolver.normalizePathSlashes("$projectDirectoryName/${mapping.folder}")
            val deltaResources = PathResolver.normalizePathSlashes(
                Path.of(mapping.folder).relativize(Path.of(relativePath)).toString()
            ).ifEmpty { "null" }
            runAntTarget(projectRoot, refreshed, mapping.antTarget, deltaResourcesPath, deltaResources)
        }
        if (!hookMatched && hookMappings.isNotEmpty()) {
            log("No hook mapping matched for: $relativePath")
        }

        if (refreshed.zideAutoResourceCopyRaw.isNullOrBlank()) {
            log("No auto-copy mappings configured. Skipping resource copy for: ${Path.of(filePath).fileName}")
        } else {
            runAutoCopyForFile(projectRoot, refreshed, filePath, projectDirectoryName)
        }
    }

    fun handleFileDelete(filePath: String) {
        val projectRoot = project.basePath ?: return
        val server = getServerForProject() ?: return
        val resolved = resolveServerMacros(server)
        val projectDirectoryName = Path.of(projectRoot).fileName.toString()

        val mappings = ModuleZidePropsParser.parseAutoCopyMappings(resolved.zideAutoResourceCopyRaw, projectDirectoryName)
        for (mapping in mappings) {
            val sourceRoot = Path.of(projectRoot, mapping.sourcePath).toString()
            if (!PathResolver.isSubPath(sourceRoot, filePath)) continue

            val subPath = Path.of(sourceRoot).relativize(Path.of(filePath)).toString()
            val destinationRoot = Path.of(
                resolved.path,
                PathResolver.applyProjectNamePlaceholder(mapping.destinationPathTemplate, projectDirectoryName)
            )
            val destinationPath = destinationRoot.resolve(subPath)

            if (destinationPath.exists()) {
                try {
                    Files.delete(destinationPath)
                    log("Deleted from deployment: $destinationPath")
                    ideaLog.info("Deploy sync: deleted $destinationPath (source: $filePath)")
                } catch (e: Exception) {
                    log("Failed to delete from deployment: $destinationPath — ${e.message}")
                }
            }
        }
    }

    override fun dispose() {
        lastExecutionByPath.clear()
    }

    companion object {
        fun getInstance(project: Project): ResourceSyncManager =
            project.getService(ResourceSyncManager::class.java)
    }
}
