package com.zoho.dzide.tomcat

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.parser.ModuleZidePropsParser
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import com.zoho.dzide.util.ShellUtil
import com.zoho.dzide.zide.DeploymentConfigPatcher
import com.zoho.dzide.zide.ZideConfigParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

@Service(Service.Level.PROJECT)
class TomcatManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(TomcatManager::class.java)

    var consoleView: ConsoleView? = null
    var appLogsConsoleView: ConsoleView? = null
    private val serverProcesses = ConcurrentHashMap<String, OSProcessHandler>()
    private val serverProvider: TomcatServerProvider
        get() = TomcatServerProvider.getInstance(project)

    fun ensureToolWindow(callback: () -> Unit) {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
        if (toolWindow == null) {
            com.zoho.dzide.util.NotificationUtil.error(project, "SAS-ZIDE tool window not found.")
            return
        }
        toolWindow.activate {
            callback()
        }
    }

    private fun log(message: String) {
        val timestamped = "[${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}] $message\n"
        consoleView?.print(timestamped, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun logError(message: String) {
        val timestamped = "[${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}] $message\n"
        consoleView?.print(timestamped, ConsoleViewContentType.ERROR_OUTPUT)
    }

    private val suppressedStderrPatterns = listOf(
        "too many arguments",
        "Picked up JDK_JAVA_OPTIONS",
        "validation was turned on but",
        "Document root element",
        "Document is invalid: no grammar found"
    )

    private fun shouldSuppressStderr(line: String): Boolean {
        return suppressedStderrPatterns.any { line.contains(it, ignoreCase = true) }
    }

    fun normalizeContextPath(contextPath: String?): String {
        val raw = (contextPath ?: "ROOT").trim()
        if (raw.isEmpty() || raw == "/") return "ROOT"
        val cleaned = raw.trimStart('/')
        return cleaned.ifEmpty { "ROOT" }
    }

    fun getApplicationUrl(server: TomcatServer, contextPath: String): String {
        val normalized = normalizeContextPath(contextPath)
        return if (normalized == "ROOT") {
            "http://localhost:${server.port}"
        } else {
            "http://localhost:${server.port}/$normalized"
        }
    }

    private fun resolveEffectiveLaunchArgs(server: TomcatServer): String? {
        val latestFromProperties = server.zidePropertiesPath?.let {
            ModuleZidePropsParser.readLaunchVmArgumentsFromProperties(it)
        }
        if (latestFromProperties != null && latestFromProperties != server.zideLaunchVmArguments) {
            serverProvider.updateServer(server.id, mapOf("zideLaunchVmArguments" to latestFromProperties))
        }
        val zideArgs = (latestFromProperties ?: server.zideLaunchVmArguments ?: "").trim()
        val manualArgs = (server.manualLaunchArgs ?: "").trim()
        val merged = listOf(zideArgs, manualArgs).filter { it.isNotEmpty() }.joinToString(" ").trim()
        return merged.ifEmpty { null }
    }

    private fun buildCatalinaEnvVars(server: TomcatServer, debugPort: Int? = null): Map<String, String> {
        val env = mutableMapOf("CATALINA_PID" to "pid.file")
        if (debugPort != null) {
            env["JPDA_ADDRESS"] = "*:$debugPort"
            env["JPDA_TRANSPORT"] = "dt_socket"
        }
        val launchArgs = resolveEffectiveLaunchArgs(server)
        if (launchArgs != null) {
            env["CATALINA_OPTS"] = launchArgs
            log("Applying launch VM arguments for ${server.name}.")
        }
        return env
    }

    @Suppress("UNUSED_PARAMETER")
    fun patchDeploymentConfigs(server: TomcatServer) {
        val projectPath = project.basePath ?: return
        ZideConfigParser.clearCache(projectPath)
        val zideConfig = ZideConfigParser.readZideConfig(projectPath) ?: return
        val serviceProps = zideConfig.service?.properties ?: return
        val zideProps = zideConfig.properties?.properties ?: emptyMap()

        val patchCtx = DeploymentConfigPatcher.buildPatchContext(serviceProps, zideProps)
        if (patchCtx == null) {
            log("Skipping config patching: missing DEPLOYMENT_FOLDER or PARENT_SERVICE.")
            return
        }

        log("Patching deployment configs for ${patchCtx.parentService}...")
        val result = DeploymentConfigPatcher.patchAll(patchCtx, project)

        if (result.serverXmlPatched) log("  Patched server.xml (Context element, shutdown port)")
        if (result.webXmlPatched) log("  Patched web.xml (JSP servlet for dynamic compilation)")
        if (result.persistencePatched) log("  Patched persistence-configurations.xml (DBName, DSAdapter, StartDBServer)")
        if (result.securityPatched) log("  Patched security-properties.xml (IAM server, service name, logout URL)")
        if (result.configPropertiesPatched) log("  Patched configuration.properties (DB driver, URL, port, vendor, credentials)")
        if (result.httpsConnectorPatched) log("  Patched HTTPS Connector (port 8443, SSL keystore)")
        if (result.keystoreDownloaded) log("  Downloaded sas.keystore to tomcat/conf/")
        for (err in result.errors) {
            logError("  Patch error: $err")
        }
        if (!result.serverXmlPatched && !result.webXmlPatched && !result.persistencePatched && !result.securityPatched && !result.configPropertiesPatched && result.errors.isEmpty()) {
            log("  Config files already up to date.")
        }
    }

    /**
     * Runs postzidedeploy.sh from the project's resources/zide-scripts/ directory.
     * This script copies app.properties into the deployment's WEB-INF/conf/ folder.
     */
    private fun runPostZideDeployScript(server: TomcatServer) {
        val projectPath = project.basePath ?: return
        val scriptPath = Path.of(projectPath, "resources", "zide-scripts", "postzidedeploy.sh")
        if (!scriptPath.exists()) {
            log("postzidedeploy.sh not found at $scriptPath, skipping.")
            return
        }

        val deploymentFolder = server.zideRuntimeProperties?.get("ZIDE.DEPLOYMENT_FOLDER") ?: return
        val deploymentBase = Path.of(deploymentFolder, "AdventNet", "Sas").toString()

        log("Running postzidedeploy.sh...")
        val command = ShellUtil.buildShellCommand(
            "chmod", "+x", "\"$scriptPath\"",
            "&&", "sh", "\"$scriptPath\"", "\"$deploymentBase\""
        )
        val result = com.zoho.dzide.util.ProcessUtil.executeCapturing(
            command = command,
            workingDir = deploymentFolder,
            timeoutMs = 30_000
        )
        if (result.stdout.isNotBlank()) log(result.stdout.trim())
        if (result.stderr.isNotBlank()) logError(result.stderr.trim())
        if (result.exitCode == 0) {
            log("postzidedeploy.sh completed successfully.")
        } else {
            logError("postzidedeploy.sh failed with exit code ${result.exitCode}")
        }
    }

    /**
     * Copies server.xml files to the correct locations before Tomcat starts.
     *
     * Step 1: Copy tomcat/conf/server.xml → webapps/{parentService}/WEB-INF/conf/server.xml
     *         (the app needs a copy of the current tomcat conf server.xml)
     * Step 2: Copy Servers/{parentService}-config/server.xml → tomcat/conf/server.xml
     *         (Eclipse's managed server.xml with Context element, SSL, etc. becomes the active tomcat config)
     *
     * These two server.xml files have different content — each copy is verified before proceeding.
     */
    private fun syncServerXmlFiles(server: TomcatServer) {
        val deploymentFolder = server.zideRuntimeProperties?.get("ZIDE.DEPLOYMENT_FOLDER") ?: return
        val parentService = server.zideRuntimeProperties?.get("ZIDE.PARENT_SERVICE")
            ?: run {
                val projectPath = project.basePath ?: return
                val zideConfig = ZideConfigParser.readZideConfig(projectPath) ?: return
                zideConfig.service?.properties?.get("ZIDE.PARENT_SERVICE") ?: return
            }

        val tomcatConfDir = Path.of(deploymentFolder, "AdventNet", "Sas", "tomcat", "conf")
        val tomcatConfServerXml = tomcatConfDir.resolve("server.xml")
        val webappConfDir = Path.of(deploymentFolder, "AdventNet", "Sas", "tomcat", "webapps", parentService, "WEB-INF", "conf")
        val webappConfServerXml = webappConfDir.resolve("server.xml")

        // Resolve Servers/{parentService}-config/ relative to workspace root
        // Deployment folder is {workspace}/deployment/{service}, so workspace = deployment/../..
        val workspaceRoot = Path.of(deploymentFolder).parent?.parent
        val serversConfigDir = workspaceRoot?.resolve("Servers")?.resolve("$parentService-config")
        val serversServerXml = serversConfigDir?.resolve("server.xml")

        log("Syncing server.xml files...")

        // Step 1: Copy tomcat/conf/server.xml → webapps/{parentService}/WEB-INF/conf/server.xml
        if (tomcatConfServerXml.exists() && webappConfDir.exists()) {
            Files.copy(tomcatConfServerXml, webappConfServerXml, StandardCopyOption.REPLACE_EXISTING)
            log("  Copied tomcat/conf/server.xml → webapps/$parentService/WEB-INF/conf/server.xml")
        } else {
            if (!tomcatConfServerXml.exists()) logError("  tomcat/conf/server.xml not found, skipping copy to webapp.")
            if (!webappConfDir.exists()) logError("  webapps/$parentService/WEB-INF/conf/ not found, skipping copy.")
        }

        // Step 2: Copy Servers/{parentService}-config/server.xml → tomcat/conf/server.xml
        if (serversServerXml != null && serversServerXml.exists()) {
            Files.copy(serversServerXml, tomcatConfServerXml, StandardCopyOption.REPLACE_EXISTING)
            log("  Copied Servers/$parentService-config/server.xml → tomcat/conf/server.xml")
        } else {
            log("  Servers/$parentService-config/server.xml not found, skipping. Tomcat conf server.xml unchanged.")
        }
    }

    /**
     * Runs all pre-start setup steps in order before launching Tomcat:
     * 1. Execute postzidedeploy.sh (copies app.properties)
     * 2. Copy tomcat/conf/server.xml → webapp WEB-INF/conf/
     * 3. Copy Servers/{service}-config/server.xml → tomcat/conf/
     */
    private fun cleanTomcatWorkDirectory(server: TomcatServer) {
        val workDir = Path.of(server.path, "work")
        if (workDir.exists()) {
            workDir.toFile().deleteRecursively()
            Files.createDirectories(workDir)
            log("Cleaned Tomcat work directory.")
        }
    }

    fun configureProjectLibraries(server: TomcatServer) {
        val projectPath = project.basePath ?: return
        val parentService = server.zideRuntimeProperties?.get("ZIDE.PARENT_SERVICE")
            ?: ZideConfigParser.readZideConfig(projectPath)?.service?.properties?.get("ZIDE.PARENT_SERVICE")
        val webappName = PathResolver.resolveWebappDirectory(server.path, parentService) ?: return
        val libDir = Path.of(server.path, "webapps", webappName, "WEB-INF", "lib")
        if (!libDir.toFile().exists()) return

        val jars = libDir.toFile().listFiles()?.filter { it.extension == "jar" } ?: return
        if (jars.isEmpty()) return

        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                    val tableModel = libraryTable.modifiableModel

                    tableModel.getLibraryByName("ZIDE-WEB-INF-lib")?.let { tableModel.removeLibrary(it) }

                    val library = tableModel.createLibrary("ZIDE-WEB-INF-lib")
                    val libModel = library.modifiableModel
                    for (jar in jars) {
                        libModel.addRoot("jar://${jar.absolutePath}!/", OrderRootType.CLASSES)
                    }
                    libModel.commit()
                    tableModel.commit()

                    for (module in ModuleManager.getInstance(project).modules) {
                        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                        val alreadyHas = rootModel.orderEntries.any {
                            it is com.intellij.openapi.roots.LibraryOrderEntry && it.libraryName == "ZIDE-WEB-INF-lib"
                        }
                        if (!alreadyHas) {
                            rootModel.addLibraryEntry(library)
                        }
                        rootModel.commit()
                    }
                    log("Configured ${jars.size} JAR(s) from WEB-INF/lib/ as project library.")
                } catch (e: Exception) {
                    log("Failed to configure project libraries: ${e.message}")
                }
            }
        }
    }

    private fun configureCompilerOutputForDeployment(server: TomcatServer) {
        val projectPath = project.basePath ?: return
        val parentService = server.zideRuntimeProperties?.get("ZIDE.PARENT_SERVICE")
            ?: ZideConfigParser.readZideConfig(projectPath)?.service?.properties?.get("ZIDE.PARENT_SERVICE")
        val webappName = PathResolver.resolveWebappDirectory(server.path, parentService) ?: return
        val outputPath = Path.of(server.path, "webapps", webappName, "WEB-INF", "classes")
        if (!outputPath.exists()) return

        com.intellij.debugger.settings.DebuggerSettings.getInstance().RUN_HOTSWAP_AFTER_COMPILE =
            com.intellij.debugger.settings.DebuggerSettings.RUN_HOTSWAP_ALWAYS

        val outputUrl = "file://${outputPath.toAbsolutePath()}"
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                for (module in ModuleManager.getInstance(project).modules) {
                    val model = ModuleRootManager.getInstance(module).modifiableModel
                    val ext = model.getModuleExtension(CompilerModuleExtension::class.java)
                    if (ext != null && ext.compilerOutputUrl != outputUrl) {
                        ext.setCompilerOutputPath(outputUrl)
                        ext.inheritCompilerOutputPath(false)
                        model.commit()
                        log("Set compiler output to: $outputPath")
                    } else {
                        model.dispose()
                    }
                }
            }
        }
    }

    private fun buildProjectAndWait(server: TomcatServer) {
        log("Building project before server start...")
        val latch = CountDownLatch(1)
        var buildSuccess = false
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) { latch.countDown(); return@invokeLater }
            CompilerManager.getInstance(project).make(project,
                ModuleManager.getInstance(project).modules
            ) { aborted, errors, _, _ ->
                buildSuccess = !aborted && errors == 0
                if (!buildSuccess) log("Build had $errors error(s). Server will start with existing classes.")
                latch.countDown()
            }
        }
        latch.await(120, TimeUnit.SECONDS)
        if (buildSuccess) log("Project build completed successfully.")
    }

    fun runPreStartSetup(server: TomcatServer) {
        log("--- Pre-start setup ---")
        configureProjectLibraries(server)
        configureCompilerOutputForDeployment(server)
        buildProjectAndWait(server)
        cleanTomcatWorkDirectory(server)
        runPostZideDeployScript(server)
        syncServerXmlFiles(server)
        log("--- Pre-start setup complete ---")
    }

    fun startServer(server: TomcatServer) {
        val script = ShellUtil.catalinaScript(server.path)
        if (!script.exists()) {
            NotificationUtil.error(project, "Startup script not found at $script")
            return
        }

        NotificationUtil.info(project, "Starting Tomcat server: ${server.name}...")

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread

            if (PortUtil.isPortInUse(server.port)) {
                log("Server ${server.name} is already running on port ${server.port}. Stopping before restart...")
                stopServer(server)
                val maxWait = 15_000L
                val interval = 500L
                var waited = 0L
                while (waited < maxWait && PortUtil.isPortInUse(server.port)) {
                    Thread.sleep(interval)
                    waited += interval
                }
                if (PortUtil.isPortInUse(server.port)) {
                    logError("Server did not stop within ${maxWait / 1000}s. Cannot start.")
                    NotificationUtil.error(project, "Server ${server.name} did not stop. Cannot restart.")
                    return@executeOnPooledThread
                }
            }

            runPreStartSetup(server)
            patchDeploymentConfigs(server)

            log("======================================")
            log("Starting Tomcat server: ${server.name}")
            log("Script path: $script")
            log("Port: ${server.port}")
            log("======================================")

            val env = buildCatalinaEnvVars(server)
            val exportChain = ShellUtil.buildExportChain(env)
            val command = ShellUtil.buildShellCommand(
                *exportChain.toTypedArray(),
                "&&", "chmod", "+x", "\"$script\"",
                "&&", "sh", "\"$script\"", "run"
            )

            com.zoho.dzide.util.ProcessUtil.executeStreaming(
                command = command,
                workingDir = server.path,
                onStdout = { consoleView?.print(it, ConsoleViewContentType.NORMAL_OUTPUT) },
                onStderr = { if (!shouldSuppressStderr(it)) consoleView?.print(it, ConsoleViewContentType.ERROR_OUTPUT) },
                onExit = { exitCode ->
                    serverProcesses.remove(server.id)
                    log("Server process exited with code: $exitCode")
                    serverProvider.updateServer(server.id, mapOf("status" to "stopped"))
                    log("Server ${server.name} stopped.")
                    NotificationUtil.info(project, "Tomcat server ${server.name} stopped.")
                }
            ).also { handler ->
                serverProcesses[server.id] = handler
                // Wait for port to confirm startup
                Thread {
                    val running = PortUtil.waitForPort(server.port, 45000)
                    if (running) {
                        serverProvider.updateServer(server.id, mapOf("status" to "running"))
                        log("Server ${server.name} started successfully!")
                        NotificationUtil.info(project, "Tomcat server ${server.name} started successfully!")
                    } else {
                        logError("Server ${server.name} failed to start - no process on port ${server.port}")
                        NotificationUtil.error(project, "Server ${server.name} failed to start.")
                    }
                }.start()
            }
        }
    }

    fun startServerInDebug(server: TomcatServer, debugPort: Int) {
        val script = ShellUtil.catalinaScript(server.path)
        if (!script.exists()) {
            throw IllegalStateException("Startup script not found at $script")
        }

        if (PortUtil.isPortInUse(server.port)) {
            log("Server ${server.name} is already running. Skipping debug start.")
            serverProvider.updateServer(server.id, mapOf("status" to "running", "debugPort" to debugPort))
            return
        }

        if (PortUtil.isPortInUse(debugPort)) {
            throw IllegalStateException("Debug port $debugPort is already in use.")
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread

            runPreStartSetup(server)
            patchDeploymentConfigs(server)

            log("======================================")
            log("Starting Tomcat server in debug mode: ${server.name}")
            log("HTTP port: ${server.port}, Debug port: $debugPort")
            log("======================================")

            val env = buildCatalinaEnvVars(server, debugPort)
            val exportChain = ShellUtil.buildExportChain(env)
            val command = ShellUtil.buildShellCommand(
                *exportChain.toTypedArray(),
                "&&", "chmod", "+x", "\"$script\"",
                "&&", "sh", "\"$script\"", "jpda", "run"
            )

            com.zoho.dzide.util.ProcessUtil.executeStreaming(
                command = command,
                workingDir = server.path,
                onStdout = { consoleView?.print(it, ConsoleViewContentType.NORMAL_OUTPUT) },
                onStderr = { if (!shouldSuppressStderr(it)) consoleView?.print(it, ConsoleViewContentType.ERROR_OUTPUT) },
                onExit = { _ ->
                    serverProcesses.remove(server.id)
                    serverProvider.updateServer(server.id, mapOf("status" to "stopped"))
                    log("Server ${server.name} (debug) stopped.")
                    NotificationUtil.info(project, "Tomcat server ${server.name} stopped.")
                }
            ).also { handler ->
                serverProcesses[server.id] = handler
                // Only wait for HTTP port — do NOT open a raw TCP socket to the debug port.
                // JDWP interprets any non-handshake connection as a failed debugger attach and
                // kills the listener, causing "handshake failed" for the real debugger.
                Thread {
                    val httpRunning = PortUtil.waitForPort(server.port, 45000)
                    if (httpRunning) {
                        serverProvider.updateServer(server.id, mapOf("status" to "running", "debugPort" to debugPort))
                        log("Server ${server.name} started in debug mode. Debug port: $debugPort")
                    } else {
                        logError("Server ${server.name} failed to start — HTTP port ${server.port} not responding.")
                    }
                }.start()
            }
        }
    }

    fun stopServer(server: TomcatServer) {
        if (!PortUtil.isPortInUse(server.port)) {
            log("Server ${server.name} is not running on port ${server.port}")
            NotificationUtil.warn(project, "Server ${server.name} is not running!")
            serverProcesses.remove(server.id)
            serverProvider.updateServer(server.id, mapOf("status" to "stopped"))
            return
        }

        log("======================================")
        log("Stopping Tomcat server: ${server.name}")
        log("======================================")
        NotificationUtil.info(project, "Stopping Tomcat server: ${server.name}...")

        val handler = serverProcesses.remove(server.id)
        if (handler != null && !handler.isProcessTerminated) {
            handler.destroyProcess()
            log("Destroyed foreground Tomcat process for ${server.name}.")
        } else {
            // Fallback: use catalina.sh stop if we don't have the process handle
            val script = ShellUtil.catalinaScript(server.path)
            if (!script.exists()) {
                NotificationUtil.error(project, "Shutdown script not found at $script")
                return
            }
            log("No attached process found. Falling back to catalina.sh stop.")
            val command = ShellUtil.buildShellCommand(
                "export", "CATALINA_PID=pid.file",
                "&&", "chmod", "+x", "\"$script\"",
                "&&", "sh", "\"$script\"", "stop", "-force"
            )
            com.zoho.dzide.util.ProcessUtil.executeStreaming(
                command = command,
                workingDir = server.path,
                onStdout = { log(it) },
                onStderr = { logError("STDERR: $it") },
                onExit = { _ -> }
            )
        }

        // Verify shutdown, fallback to lsof + kill if still running
        Thread {
            Thread.sleep(3000)
            var stillRunning = PortUtil.isPortInUse(server.port)
            if (stillRunning) {
                log("Server still running on port ${server.port}. Attempting force kill via lsof...")
                forceKillByPort(server.port)
                Thread.sleep(2000)
                stillRunning = PortUtil.isPortInUse(server.port)
            }
            if (!stillRunning) {
                serverProvider.updateServer(server.id, mapOf("status" to "stopped"))
                log("Server ${server.name} stopped successfully!")
                NotificationUtil.info(project, "Tomcat server ${server.name} stopped successfully!")
            } else {
                serverProvider.updateServer(server.id, mapOf("status" to "running"))
                logError("Server ${server.name} could not be stopped on port ${server.port}")
                NotificationUtil.error(project, "Failed to stop server ${server.name}. Manual intervention required.")
            }
        }.start()
    }

    private fun forceKillByPort(port: Int) {
        try {
            val lsofResult = com.zoho.dzide.util.ProcessUtil.executeCapturing(
                command = listOf("lsof", "-ti", ":$port"),
                timeoutMs = 5000
            )
            val pids = lsofResult.stdout.trim().lines().filter { it.isNotBlank() }
            if (pids.isEmpty()) {
                log("No PIDs found via lsof for port $port")
                return
            }
            for (pid in pids) {
                log("Killing PID $pid on port $port")
                com.zoho.dzide.util.ProcessUtil.executeCapturing(
                    command = listOf("kill", "-9", pid),
                    timeoutMs = 5000
                )
            }
            log("Force kill sent for PIDs: ${pids.joinToString(", ")}")
        } catch (ex: Exception) {
            logError("Force kill failed: ${ex.message}")
        }
    }

    fun refreshAllServerStatus() {
        log("Refreshing status for all servers...")
        for (server in serverProvider.getServers()) {
            val isRunning = PortUtil.isPortInUse(server.port)
            val newStatus = if (isRunning) "running" else "stopped"
            if (server.status != newStatus) {
                log("${server.name}: Status updated from ${server.status} to $newStatus")
                serverProvider.updateServer(server.id, mapOf("status" to newStatus))
            } else {
                log("${server.name}: Status confirmed as ${server.status}")
            }
        }
        log("Status refresh completed.")
    }

    fun deployWarFile(server: TomcatServer, warFile: String, contextPath: String) {
        val webappsDir = Path.of(server.path, "webapps")
        val normalized = normalizeContextPath(contextPath)
        val deployedDir = webappsDir.resolve(normalized)
        val targetWarName = if (normalized == "ROOT") "ROOT.war" else "$normalized.war"
        val targetWarFile = webappsDir.resolve(targetWarName)

        log("Deploying ${Path.of(warFile).fileName} to ${server.name} as $targetWarName")
        NotificationUtil.info(project, "Deploying application to ${server.name}...")

        if (deployedDir.exists()) {
            deployedDir.toFile().deleteRecursively()
        }
        Files.deleteIfExists(targetWarFile)
        Files.copy(Path.of(warFile), targetWarFile, StandardCopyOption.REPLACE_EXISTING)

        NotificationUtil.info(project, "Deployment completed on ${server.name}.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun runProjectOnServer(
        server: TomcatServer,
        projectPath: String,
        contextPath: String,
        preferredWarFilePath: String?
    ): String? {
        val warFile = resolveConfiguredWarFile(preferredWarFilePath)
        val isRunning = PortUtil.isPortInUse(server.port)

        if (warFile != null) {
            deployWarFile(server, warFile, contextPath)
        } else {
            log("No WAR configured for ${server.name}. Proceeding without deployment.")
        }

        if (!isRunning) {
            startServer(server)
        }
        return warFile
    }

    @Suppress("UNUSED_PARAMETER")
    fun debugProjectOnServer(
        server: TomcatServer,
        projectPath: String,
        contextPath: String,
        preferredWarFilePath: String?
    ): Int? {
        val debugPort = PortUtil.findAvailablePort(server.debugPort ?: 5005)
        val warFile = resolveConfiguredWarFile(preferredWarFilePath)

        if (warFile != null) {
            deployWarFile(server, warFile, contextPath)
        } else {
            log("No WAR configured for ${server.name}. Proceeding with debug start without deployment.")
        }

        val isRunning = PortUtil.isPortInUse(server.port)
        if (!isRunning) {
            startServerInDebug(server, debugPort)
        } else {
            val debugActive = PortUtil.isPortInUse(debugPort)
            if (!debugActive) {
                stopServer(server)
                Thread.sleep(2000)
                startServerInDebug(server, debugPort)
            } else {
                serverProvider.updateServer(server.id, mapOf("debugPort" to debugPort))
            }
        }
        return debugPort
    }

    private fun resolveConfiguredWarFile(preferredWarFilePath: String?): String? {
        if (preferredWarFilePath != null && Path.of(preferredWarFilePath).exists()) {
            log("Using saved WAR path: $preferredWarFilePath")
            return preferredWarFilePath
        }
        if (preferredWarFilePath != null && !Path.of(preferredWarFilePath).exists()) {
            NotificationUtil.warn(project, "Configured WAR file path is no longer valid. Continuing without deployment.")
        }
        return null
    }

    override fun dispose() {
        serverProcesses.values.forEach { handler ->
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
            }
        }
        for (server in serverProvider.getServers()) {
            if (server.status == "running" && PortUtil.isPortInUse(server.port)) {
                forceKillByPort(server.port)
            }
        }
        serverProcesses.clear()
    }

    companion object {
        fun getInstance(project: Project): TomcatManager =
            project.getService(TomcatManager::class.java)
    }
}
