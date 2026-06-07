package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.PortUtil
import com.zoho.dzide.util.ProcessUtil
import com.zoho.dzide.util.ShellUtil
import com.zoho.dzide.zide.DeploymentConfigPatcher
import com.zoho.dzide.zide.ZideConfigParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name

class UpdateDeploymentAction : AnAction("Local Build", "Deploy a local zip file to the server", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tomcatManager = TomcatManager.getInstance(project)

        val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
            .withTitle("Select Zip File to Deploy")
            .withDescription("Choose a .zip file to deploy to the server")
            .withFileFilter { it.extension == "zip" }

        val files = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
        val selectedFile = files.firstOrNull() ?: return
        val zipPath = Path.of(selectedFile.path)

        val server = ServerActionUtil.getSelectedServer(e)
        if (server != null) {
            runDeployment(project, server, tomcatManager, zipPath)
        } else {
            runDeploymentWithoutServer(project, tomcatManager, zipPath)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    companion object {

        fun runDeploymentWithoutServer(project: Project, tomcatManager: TomcatManager, zipPath: Path) {
            val projectPath = project.basePath
            val zideConfig = projectPath?.let { ZideConfigParser.readZideConfig(it) }
            val deploymentFolder = zideConfig?.service?.properties?.get("ZIDE.DEPLOYMENT_FOLDER")
            if (deploymentFolder.isNullOrBlank()) {
                NotificationUtil.error(project, "ZIDE.DEPLOYMENT_FOLDER not found in .zide_resources/service.xml. Add a Tomcat server first.")
                return
            }
            val parentService = zideConfig.service?.properties?.get("ZIDE.PARENT_SERVICE") ?: ""
            val tomcatPath = "$deploymentFolder/AdventNet/Sas/tomcat"

            val tempServer = TomcatServer(
                name = parentService.ifBlank { "ZIDE" },
                path = tomcatPath,
                zideRuntimeProperties = zideConfig.service?.properties
            )
            runDeployment(project, tempServer, tomcatManager, zipPath)
        }

        fun runDeployment(project: Project, server: TomcatServer, tomcatManager: TomcatManager, zipPath: Path) {
            tomcatManager.ensureToolWindow {
                val console = tomcatManager.consoleView ?: return@ensureToolWindow

                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
                val outputContent = toolWindow?.contentManager?.findContent("Output")
                if (outputContent != null) {
                    toolWindow.contentManager.setSelectedContent(outputContent)
                }

                console.clear()
                ApplicationManager.getApplication().executeOnPooledThread {
                    runDeploymentCore(project, server, tomcatManager, zipPath, console)
                }
            }
        }

        fun runDeploymentCore(project: Project, server: TomcatServer, tomcatManager: TomcatManager, zipPath: Path, console: ConsoleView) {
            val deploymentFolder = server.zideRuntimeProperties?.get("ZIDE.DEPLOYMENT_FOLDER")
            if (deploymentFolder.isNullOrBlank()) {
                NotificationUtil.error(project, "ZIDE.DEPLOYMENT_FOLDER not configured for this server.")
                return
            }
            val deployDir = Path.of(deploymentFolder)

            val projectPath = project.basePath
            val repositoryPath = readRepositoryPath(projectPath)
            val deploymentPath = server.path

            if (repositoryPath == null) {
                NotificationUtil.error(project, "Could not read 'repositorypath' from .zide_resources/repository.properties.")
                return
            }

            val zideConfig = ZideConfigParser.readZideConfig(repositoryPath)
            val parentService = zideConfig?.service?.properties?.get("ZIDE.PARENT_SERVICE")
            if (parentService.isNullOrBlank()) {
                NotificationUtil.error(project, "Could not read ZIDE.PARENT_SERVICE from service.xml.")
                return
            }

            val zideProps = zideConfig.properties?.properties ?: emptyMap()
            val dbUser = zideProps["ZIDE.DB_USER"] ?: "root"
            val dbName = zideProps["ZIDE.DB_NAME"] ?: parentService.lowercase()
            val dbPass = zideProps["ZIDE.DB_PASS"] ?: ""

            ConsoleUtil.print(console, project, "=== Update Deployment ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ConsoleUtil.print(console, project, "Zip file: $zipPath\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ConsoleUtil.print(console, project, "Deploy to: $deployDir\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ConsoleUtil.print(console, project, "Repository: $repositoryPath\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ConsoleUtil.print(console, project, "Service: $parentService\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                    try {
                        if (PortUtil.isPortInUse(server.port)) {
                            ConsoleUtil.print(console, project, "[Stop] Server ${server.name} is running. Stopping...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed) {
                                    tomcatManager.stopServer(server)
                                }
                            }
                            val stopped = PortUtil.waitForPortRelease(server.port, 30000)
                            if (stopped) {
                                ConsoleUtil.print(console, project, "[Stop] Server stopped.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            } else {
                                ConsoleUtil.print(console, project, "[Stop] WARNING: Server may still be running. Proceeding anyway.\n\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                            }
                        }

                        Files.createDirectories(deployDir)
                        val destZip = deployDir.resolve(zipPath.name)

                        ConsoleUtil.print(console, project, "[1/6] Copying ${zipPath.name} to $deployDir...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        Files.copy(zipPath, destZip, StandardCopyOption.REPLACE_EXISTING)
                        ConsoleUtil.print(console, project, "Copied successfully.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        ConsoleUtil.print(console, project, "[2/6] Extracting ${zipPath.name}...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val unzipResult = ProcessUtil.executeCapturing(
                            command = listOf("unzip", "-o", destZip.toString(), "-d", deployDir.toString()),
                            workingDir = deployDir.toString(),
                            timeoutMs = 120_000
                        )
                        if (unzipResult.stdout.isNotBlank()) {
                            ConsoleUtil.print(console, project, unzipResult.stdout + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                        if (unzipResult.stderr.isNotBlank()) {
                            ConsoleUtil.print(console, project, unzipResult.stderr + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }
                        if (unzipResult.exitCode != 0) {
                            ConsoleUtil.print(console, project, "\nExtract FAILED (exit code ${unzipResult.exitCode})\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "Extraction failed.")
                            return
                        }
                        ConsoleUtil.print(console, project, "Extracted successfully.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        val tomcatDir = deployDir.resolve("AdventNet").resolve("Sas").resolve("tomcat")
                        val workDir = tomcatDir.resolve("work")
                        if (Files.exists(workDir)) {
                            ConsoleUtil.print(console, project, "Cleaning Tomcat work directory...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            workDir.toFile().deleteRecursively()
                            Files.createDirectories(workDir)
                        }

                        val webappsDir = tomcatDir.resolve("webapps")
                        val rootWar = webappsDir.resolve("ROOT.war")
                        if (!rootWar.exists()) {
                            ConsoleUtil.print(console, project, "ERROR: ROOT.war not found at $rootWar\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "ROOT.war not found in webapps.")
                            return
                        }

                        val productDir = webappsDir.resolve(parentService)
                        if (Files.exists(productDir)) {
                            ConsoleUtil.print(console, project, "Cleaning old deployment at $parentService/...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            productDir.toFile().deleteRecursively()
                        }
                        Files.createDirectories(productDir)
                        ConsoleUtil.print(console, project, "[3/6] Unzipping ROOT.war as $parentService...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val warUnzipResult = ProcessUtil.executeCapturing(
                            command = listOf("unzip", "-o", rootWar.toString(), "-d", productDir.toString()),
                            workingDir = webappsDir.toString(),
                            timeoutMs = 120_000
                        )
                        if (warUnzipResult.stdout.isNotBlank()) {
                            ConsoleUtil.print(console, project, warUnzipResult.stdout + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                        if (warUnzipResult.stderr.isNotBlank()) {
                            ConsoleUtil.print(console, project, warUnzipResult.stderr + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }
                        if (warUnzipResult.exitCode != 0) {
                            ConsoleUtil.print(console, project, "\nWAR extract FAILED (exit code ${warUnzipResult.exitCode})\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "ROOT.war extraction failed.")
                            return
                        }
                        ConsoleUtil.print(console, project, "ROOT.war extracted to $parentService/\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        // Extract all other .war files into their own directories
                        Files.list(webappsDir).use { stream ->
                            stream.filter { it.extension == "war" && it.name != "ROOT.war" }
                                .forEach { warFile ->
                                    val warName = warFile.name.removeSuffix(".war")
                                    val warDir = webappsDir.resolve(warName)
                                    Files.createDirectories(warDir)
                                    ConsoleUtil.print(console, project, "Extracting ${warFile.name} as $warName...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                    val extraWarResult = ProcessUtil.executeCapturing(
                                        command = listOf("unzip", "-o", warFile.toString(), "-d", warDir.toString()),
                                        workingDir = webappsDir.toString(),
                                        timeoutMs = 120_000
                                    )
                                    if (extraWarResult.exitCode == 0) {
                                        ConsoleUtil.print(console, project, "${warFile.name} extracted to $warName/\n", ConsoleViewContentType.NORMAL_OUTPUT)
                                    } else {
                                        ConsoleUtil.print(console, project, "WARNING: Failed to extract ${warFile.name} (exit code ${extraWarResult.exitCode})\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                                    }
                                }
                        }

                        ConsoleUtil.print(console, project, "[4/6] Deleting *.war files from webapps...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        Files.list(webappsDir).use { stream ->
                            stream.filter { it.extension == "war" }.forEach { warFile ->
                                Files.delete(warFile)
                                ConsoleUtil.print(console, project, "Deleted: ${warFile.name}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                            }
                        }
                        ConsoleUtil.print(console, project, "WAR files cleaned up.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        val antHome = AntResolver.resolveAntHome(projectPath!!, server.antHomeResolvedPath)
                        if (antHome == null) {
                            ConsoleUtil.print(console, project, "ERROR: ANT not found. Set ANT_HOME in ~/.zshrc\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "ANT not found. Set ANT_HOME in ~/.zshrc")
                            return
                        }
                        val antExec = AntResolver.resolveAntExecutable(antHome)
                        val hookBaseDir = Path.of(repositoryPath, ".zide_resources", "zide_hook").toString()
                        val buildXml = Path.of(hookBaseDir, "build.xml").toString()

                        if (!Path.of(buildXml).exists()) {
                            ConsoleUtil.print(console, project, "ERROR: build.xml not found at $buildXml. Hooks skipped.\n", ConsoleViewContentType.ERROR_OUTPUT)
                            NotificationUtil.error(project, "build.xml not found. Hooks skipped.")
                            return
                        }

                        ConsoleUtil.print(console, project, "[5/6] Running ANT hooks...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        ConsoleUtil.print(console, project, "  Running pre-creation hook...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val preCreationOk = runAntHook(
                            console, project, antExec, buildXml, hookBaseDir,
                            "precreationhook", repositoryPath, deploymentPath, parentService,
                            emptyMap()
                        )
                        if (!preCreationOk) {
                            ConsoleUtil.print(console, project, "  Pre-creation hook FAILED.\n\n", ConsoleViewContentType.ERROR_OUTPUT)
                        } else {
                            ConsoleUtil.print(console, project, "  Pre-creation hook completed.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        }

                        val postHookBaseDir = Path.of(repositoryPath, ".zide_resources", "zide_build").toString()
                        val postBuildXml = Path.of(postHookBaseDir, "build.xml").toString()
                        ConsoleUtil.print(console, project, "  Running post-creation hook...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val postCreationOk = runAntHook(
                            console, project, antExec, postBuildXml, postHookBaseDir,
                            "postservicetarget", repositoryPath, deploymentPath, parentService,
                            mapOf(
                                "ZIDE_DB_USER" to dbUser,
                                "ZIDE_DB_NAME" to dbName,
                                "ZIDE_DB_PASS" to dbPass
                            )
                        )
                        if (!postCreationOk) {
                            ConsoleUtil.print(console, project, "  Post-creation hook FAILED.\n\n", ConsoleViewContentType.ERROR_OUTPUT)
                        } else {
                            ConsoleUtil.print(console, project, "  Post-creation hook completed.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        }

                        ConsoleUtil.print(console, project, "  Running zide module hook...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val moduleHookOk = runAntHook(
                            console, project, antExec, buildXml, hookBaseDir,
                            "zidemodulehook", repositoryPath, deploymentPath, parentService,
                            emptyMap()
                        )
                        if (!moduleHookOk) {
                            ConsoleUtil.print(console, project, "  Zide module hook FAILED.\n\n", ConsoleViewContentType.ERROR_OUTPUT)
                        } else {
                            ConsoleUtil.print(console, project, "  Zide module hook completed.\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        }

                        ConsoleUtil.print(console, project, "[6/6] Patching deployment config files...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        val patchCtx = DeploymentConfigPatcher.buildPatchContext(
                            zideConfig.service?.properties ?: emptyMap(),
                            zideProps
                        )
                        if (patchCtx != null) {
                            val patchResult = DeploymentConfigPatcher.patchAll(patchCtx, project)
                            if (patchResult.serverXmlPatched)
                                ConsoleUtil.print(console, project, "  Patched server.xml (Context element, shutdown port)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (patchResult.webXmlPatched)
                                ConsoleUtil.print(console, project, "  Patched web.xml (JSP servlet for dynamic compilation)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (patchResult.persistencePatched)
                                ConsoleUtil.print(console, project, "  Patched persistence-configurations.xml\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (patchResult.securityPatched)
                                ConsoleUtil.print(console, project, "  Patched security-properties.xml\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (patchResult.httpsConnectorPatched)
                                ConsoleUtil.print(console, project, "  Patched HTTPS Connector (port 8443, SSL keystore)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            if (patchResult.keystoreDownloaded)
                                ConsoleUtil.print(console, project, "  Downloaded sas.keystore to tomcat/conf/\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            for (err in patchResult.errors) {
                                ConsoleUtil.print(console, project, "  Patch error: $err\n", ConsoleViewContentType.ERROR_OUTPUT)
                            }
                            if (!patchResult.serverXmlPatched && !patchResult.webXmlPatched && !patchResult.persistencePatched && !patchResult.securityPatched && patchResult.errors.isEmpty()) {
                                ConsoleUtil.print(console, project, "  Config files already up to date.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                            }
                        } else {
                            ConsoleUtil.print(console, project, "  Skipped: missing DEPLOYMENT_FOLDER or PARENT_SERVICE.\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                        }
                        ConsoleUtil.print(console, project, "\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                        ConsoleUtil.print(console, project, "=== Deployment update completed ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        NotificationUtil.info(project, "Deployment updated successfully.")

                    } catch (ex: Exception) {
                        ConsoleUtil.print(console, project, "Error: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        NotificationUtil.error(project, "Update deployment failed: ${ex.message}")
                    }
        }

        fun readRepositoryPath(projectPath: String?): String? {
            if (projectPath == null) return null
            val repoPropsFile = Path.of(projectPath, ".zide_resources", "repository.properties")
            if (repoPropsFile.exists()) {
                try {
                    val props = Properties()
                    Files.newInputStream(repoPropsFile).use { props.load(it) }
                    val value = props.getProperty("repositorypath")?.trim()?.ifEmpty { null }
                    if (value != null) return value
                } catch (_: Exception) { }
            }
            val zideResources = Path.of(projectPath, ".zide_resources")
            if (zideResources.exists()) {
                return zideResources.parent.toAbsolutePath().normalize().toString()
            }
            return null
        }

        fun runAntHook(
            console: ConsoleView,
            project: Project,
            antExec: String,
            buildXml: String,
            buildBaseDir: String,
            target: String,
            repositoryPath: String,
            deploymentPath: String,
            parentService: String,
            extraProps: Map<String, String>
        ): Boolean {
            val extraArgs = extraProps.entries.joinToString(" ") {
                "-D${it.key}=${ShellUtil.shellEscapeSingleQuoted(it.value)}"
            }

            val command = ShellUtil.buildShellCommand(
                "\"$antExec\"",
                "-f", "\"$buildXml\"",
                "-Dbasedir=\"$buildBaseDir\"",
                "clone",
                "-Dtarget=$target",
                "-DREPOSITORY_PATH=$repositoryPath",
                "-DDEPLOYMENT_PATH=$deploymentPath",
                "-DZIDE.PARENT_SERVICE=$parentService",
                extraArgs
            )

            ConsoleUtil.print(console, project, "$ ${command.last()}\n", ConsoleViewContentType.SYSTEM_OUTPUT)

            val result = ProcessUtil.executeCapturing(
                command = command,
                workingDir = repositoryPath,
                timeoutMs = 300_000
            )

            if (result.stdout.isNotBlank()) {
                ConsoleUtil.print(console, project, result.stdout + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
            }
            if (result.stderr.isNotBlank()) {
                ConsoleUtil.print(console, project, result.stderr + "\n", ConsoleViewContentType.ERROR_OUTPUT)
            }

            return result.exitCode == 0
        }
    }
}
