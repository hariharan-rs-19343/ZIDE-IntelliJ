package com.zoho.dzide.newproject

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.zoho.dzide.settings.ZideSettingsState
import com.zoho.dzide.util.ProcessUtil
import com.zoho.dzide.zide.DeploymentConfigPatcher
import com.zoho.dzide.zide.DeploymentPropertiesDialog
import com.zoho.dzide.zide.ZideConfigParser
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

class ZideNewProjectAction : AnAction("New Project", "Create a new ZIDE project", null) {

    private val log = Logger.getInstance(ZideNewProjectAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        if (!ensureCmToolToken()) return

        val dialog = ZideProjectWizardDialog(project)
        if (!dialog.showAndGet()) return

        val result = dialog.getResult()
        if (result.name.isBlank()) {
            Messages.showErrorDialog("Project name cannot be empty.", "New ZIDE Project")
            return
        }

        val projectDir = File(result.location, result.name)
        val deploymentDir = File(result.location, "deployment/${result.name}")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating ZIDE Project: ${result.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val startTime = System.currentTimeMillis()
                    log.info("Service creation for ${result.serviceName} (${result.name}) started")

                    // Step 1: Clone repository
                    if (result.repositoryUrl.isNotBlank()) {
                        indicator.text = "Cloning repository..."
                        indicator.fraction = 0.05
                        cloneRepository(result.repositoryUrl, result.branch, projectDir, indicator)
                    } else {
                        indicator.text = "Creating project directory..."
                        indicator.fraction = 0.0
                        Files.createDirectories(projectDir.toPath())
                    }

                    // Step 2: Add .gitignore entries
                    indicator.text = "Configuring .gitignore..."
                    indicator.fraction = 0.10
                    addGitIgnoreEntries(projectDir)

                    // Step 3: Create .zide_resources folder
                    indicator.text = "Creating ZIDE metadata..."
                    indicator.fraction = 0.12
                    val zideResourcesDir = File(projectDir, ".zide_resources")
                    if (!zideResourcesDir.exists()) {
                        zideResourcesDir.mkdirs()
                    }

                    // Step 4: Download build archive
                    val buildZip: Path? = when (result.buildType) {
                        "remote" -> {
                            if (result.buildUrl.isNotBlank()) {
                                indicator.text = "Downloading build..."
                                indicator.fraction = 0.20
                                downloadBuild(result.buildUrl, result.name, indicator)
                            } else null
                        }
                        "local" -> {
                            if (result.localBuildPath.isNotBlank()) {
                                val localFile = File(result.localBuildPath)
                                if (localFile.exists()) localFile.toPath() else null
                            } else null
                        }
                        else -> null
                    }

                    // Step 5: Extract into deployment folder
                    var hasBuild = false
                    if (buildZip != null && Files.exists(buildZip) && buildZip.toFile().length() > 0) {
                        indicator.text = "Extracting deployment..."
                        indicator.fraction = 0.45
                        Files.createDirectories(deploymentDir.toPath())

                        val unzipResult = ProcessUtil.executeCapturing(
                            command = listOf("unzip", "-o", buildZip.toString(), "-d", deploymentDir.absolutePath),
                            workingDir = deploymentDir.absolutePath,
                            timeoutMs = 120_000
                        )
                        if (unzipResult.exitCode != 0) {
                            throw RuntimeException("Failed to extract build: ${unzipResult.stderr}")
                        }

                        val webappsDir = deploymentDir.toPath()
                            .resolve("AdventNet").resolve("Sas").resolve("tomcat").resolve("webapps")
                        val rootWar = webappsDir.resolve("ROOT.war")
                        if (Files.exists(rootWar)) {
                            indicator.text = "Extracting WARs..."
                            indicator.fraction = 0.55
                            val serviceDir = webappsDir.resolve(result.name)
                            Files.createDirectories(serviceDir)
                            ProcessUtil.executeCapturing(
                                command = listOf("unzip", "-o", rootWar.toString(), "-d", serviceDir.toString()),
                                workingDir = webappsDir.toString(),
                                timeoutMs = 120_000
                            )

                            Files.list(webappsDir).use { stream ->
                                stream.filter { it.toString().endsWith(".war") && it.fileName.toString() != "ROOT.war" }
                                    .forEach { warFile ->
                                        val warName = warFile.fileName.toString().removeSuffix(".war")
                                        val warDir = webappsDir.resolve(warName)
                                        Files.createDirectories(warDir)
                                        ProcessUtil.executeCapturing(
                                            command = listOf("unzip", "-o", warFile.toString(), "-d", warDir.toString()),
                                            workingDir = webappsDir.toString(),
                                            timeoutMs = 120_000
                                        )
                                    }
                            }

                            Files.list(webappsDir).use { stream ->
                                stream.filter { it.toString().endsWith(".war") }
                                    .forEach { Files.delete(it) }
                            }
                        }

                        if (result.buildType == "remote") {
                            Files.deleteIfExists(buildZip)
                        }
                        hasBuild = true
                    }

                    // Step 6: Create service.xml (ZIDE metadata)
                    indicator.text = "Writing service configuration..."
                    indicator.fraction = 0.58
                    writeServiceXml(projectDir, deploymentDir, result)

                    // Step 7: Create zide_properties.xml with default deployment properties
                    indicator.text = "Writing deployment properties..."
                    indicator.fraction = 0.60
                    writeZidePropertiesXml(projectDir, result)

                    // Step 8: Create repository.properties
                    indicator.text = "Writing repository properties..."
                    indicator.fraction = 0.62
                    writeRepositoryProperties(projectDir)

                    // Step 9: Create zide_build/ and zide_hook/ structures
                    indicator.text = "Creating build and hook structures..."
                    indicator.fraction = 0.64
                    createZideBuildStructure(projectDir, deploymentDir, result)

                    // Step 10: Run pre-creation + post-creation + zide-module hooks
                    if (hasBuild) {
                        indicator.text = "Running ANT hooks..."
                        indicator.fraction = 0.68
                        runHooksIfAvailable(projectDir, deploymentDir, result.name, indicator)
                    }

                    // Step 11: Show Deployment Properties dialog for user customization
                    indicator.text = "Configuring deployment properties..."
                    indicator.fraction = 0.75
                    ApplicationManager.getApplication().invokeAndWait {
                        showDeploymentProperties(projectDir, result)
                    }

                    // Step 12: Run deployment config patching (server.xml, web.xml, etc.)
                    if (hasBuild) {
                        indicator.text = "Patching deployment configs..."
                        indicator.fraction = 0.80
                        patchDeploymentConfigs(projectDir, deploymentDir, result)
                    }

                    // Step 13: Open project in IntelliJ
                    indicator.text = "Opening project..."
                    indicator.fraction = 0.90
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.ide.impl.ProjectUtil.openOrImport(projectDir.toPath(), null, true)
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    log.info("Service creation for ${result.serviceName} (${result.name}) completed in ${elapsed}ms")
                    indicator.fraction = 1.0

                } catch (ex: Exception) {
                    log.error("Failed to create ZIDE project: ${result.name}", ex)
                    rollback(projectDir, deploymentDir)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog("Failed to create project: ${ex.message}", "New ZIDE Project")
                    }
                }
            }
        })
    }

    private fun ensureCmToolToken(): Boolean {
        val settings = ZideSettingsState.getInstance()
        if (settings.cmToolAuthToken.isNotBlank()) return true

        val token = Messages.showInputDialog(
            "CMTool Auth Token is required to create a ZIDE project.\n\nEnter your CMTool Auth Token:",
            "CMTool Auth Token Required",
            null
        )

        if (token.isNullOrBlank()) {
            Messages.showWarningDialog(
                "Cannot create ZIDE project without CMTool Auth Token.\nConfigure it in Settings > Tools > Zide > CMTool.",
                "CMTool Auth Token Required"
            )
            return false
        }

        settings.cmToolAuthToken = token.trim()
        return true
    }

    private fun cloneRepository(repositoryUrl: String, branch: String, projectDir: File, indicator: ProgressIndicator) {
        val gitPath = resolveGitExecutable()
            ?: throw RuntimeException("Git not found. Configure git path in Settings > Tools > Zide > Git.")

        val branchArg = branch.ifBlank { "master" }
        indicator.text = "Cloning $repositoryUrl (branch: $branchArg)..."

        val cloneResult = ProcessUtil.executeCapturing(
            command = listOf(gitPath, "clone", "-b", branchArg, repositoryUrl, projectDir.absolutePath),
            timeoutMs = 600_000
        )
        if (cloneResult.exitCode != 0) {
            throw RuntimeException("Git clone failed (exit code ${cloneResult.exitCode}): ${cloneResult.stderr}")
        }
    }

    private fun downloadBuild(buildUrl: String, projectName: String, indicator: ProgressIndicator): Path? {
        val fileName = buildUrl.substringAfterLast('/').ifEmpty { "$projectName.zip" }
        val tempFile = Files.createTempFile("dzide-newproject-", "-$fileName")

        indicator.text = "Downloading $fileName..."

        val wgetResult = ProcessUtil.executeCapturing(
            command = listOf("wget", "--progress=dot", "-O", tempFile.toString(), buildUrl),
            timeoutMs = 600_000
        )
        if (wgetResult.exitCode != 0) {
            Files.deleteIfExists(tempFile)
            throw RuntimeException("Build download failed (exit code ${wgetResult.exitCode})")
        }
        return tempFile
    }

    private fun resolveGitExecutable(): String? {
        val settings = ZideSettingsState.getInstance()
        if (settings.gitPath.isNotBlank()) {
            val gitExe = File(settings.gitPath, "git")
            if (gitExe.exists()) return gitExe.absolutePath
        }
        val candidates = listOf("/usr/bin/git", "/usr/local/bin/git")
        for (candidate in candidates) {
            if (File(candidate).exists()) return candidate
        }
        try {
            val process = ProcessBuilder("which", "git").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.isNotEmpty() && File(result).exists()) return result
        } catch (_: Exception) {}
        return null
    }

    private fun addGitIgnoreEntries(projectDir: File) {
        val gitignoreFile = File(projectDir, ".gitignore")
        val standardEntries = listOf(
            ".zide_resources/",
            "deployment/",
            "*.class",
            "*.jar",
            "*.war",
            ".idea/",
            "*.iml",
            "out/",
            "build/",
            "bin/",
            ".classpath",
            ".project",
            ".settings/"
        )

        val existingPatterns = if (gitignoreFile.exists()) {
            gitignoreFile.readLines().map { it.trim() }.toSet()
        } else {
            emptySet()
        }

        val missing = standardEntries.filter { it !in existingPatterns }
        if (missing.isEmpty()) return

        val sb = StringBuilder()
        if (gitignoreFile.exists()) {
            val content = gitignoreFile.readText()
            sb.append(content)
            if (!content.endsWith("\n")) sb.append("\n")
            sb.append("\n# ZIDE ignore entries\n")
        }
        for (entry in missing) {
            sb.appendLine(entry)
        }
        gitignoreFile.writeText(sb.toString())
    }

    // ZideConfigParser.parseServiceXml() uses getElementsByTagName("service") which matches
    // <service> at any nesting level -- compatible with both <services><service> (our format)
    // and <zide><services><service> (some Eclipse formats). The regex fallback also matches.
    private fun writeServiceXml(projectDir: File, deploymentDir: File, result: ZideProjectWizardDialog.WizardResult) {
        val zideResources = File(projectDir, ".zide_resources")
        zideResources.mkdirs()
        val serviceXml = File(zideResources, "service.xml")

        val serviceKey = result.serviceName.ifBlank { result.name }
        val deployFolder = deploymentDir.absolutePath
        val moduleDir = result.serviceName.ifBlank { result.name }
        val buildUrl = when (result.buildType) {
            "remote" -> result.buildUrl
            "local" -> result.localBuildPath
            else -> ""
        }

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<services>")
            appendLine("""  <service key="ROOT">""")
            appendLine("""    <property name="ZIDE.REPOSITORY_TRUNK" value="${result.branch.ifBlank { "master" }}"/>""")
            appendLine("""    <property name="ZIDE.SSH_USERNAME" value="${System.getProperty("user.name", "")}"/>""")
            appendLine("""    <property name="ZIDE.REPOSITORY_MODULE_DIR" value="$moduleDir"/>""")
            appendLine("""    <property name="ZIDE.DOWNLOAD_URL" value="$buildUrl"/>""")
            appendLine("""    <property name="ZIDE.LOCAL_DOWNLOAD_URL" value="${if (result.buildType == "local") result.localBuildPath else ""}"/>""")
            appendLine("""    <property name="ZIDE.PARENT_SERVICE" value="${result.name}"/>""")
            appendLine("""    <property name="ZIDE.DEPLOYMENT_FOLDER" value="$deployFolder"/>""")
            appendLine("""    <property name="ZIDE.DEPEND_SERVICES" value="${result.dependServices}"/>""")
            appendLine("""    <property name="ZIDE.RUNNABLE_SERVICES" value=""/>""")
            appendLine("""    <property name="ZIDE.SUBMODULES" value=""/>""")
            appendLine("""    <property name="ZIDE.SERVICE_KEY" value="$serviceKey"/>""")
            appendLine("""    <property name="ZIDE.COLD_START" value="true"/>""")
            appendLine("""    <property name="ZIDE.DO_REPLACE" value="false"/>""")
            appendLine("""    <property name="ZIDE.PERMISSION" value="1"/>""")
            appendLine("""    <property name="ZIDE.SOURCES" value="src/main/java"/>""")
            appendLine("""    <property name="ZIDE.REPO_TYPE" value="2"/>""")
            appendLine("""    <property name="ZIDE.DEPLOY_TYPE" value="M19"/>""")
            appendLine("""    <property name="ZIDE.MI_DEPLOYMENT" value="false"/>""")
            appendLine("""    <property name="ZIDE.TOMCAT_VERSION" value=""/>""")
            appendLine("""    <property name="ZIDE.PROJECT_JRE_HOME" value="${result.jdkHomePath}"/>""")
            appendLine("  </service>")
            appendLine("</services>")
        }
        serviceXml.writeText(xml)
    }

    private fun writeZidePropertiesXml(projectDir: File, result: ZideProjectWizardDialog.WizardResult) {
        val zideResources = File(projectDir, ".zide_resources")
        zideResources.mkdirs()
        val propsXml = File(zideResources, "zide_properties.xml")
        if (propsXml.exists()) return

        val serviceKey = result.serviceName.ifBlank { result.name }
        val hostname = resolveHostNameWithDomain()
        val userName = System.getProperty("user.name", "")
        val userMail = "${userName}@zohocorp.com"

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<services>")
            appendLine("""  <service key="$serviceKey">""")
            appendLine("""    <property name="ZIDE.HOST_NAME" value="$hostname"/>""")
            appendLine("""    <property name="ZIDE.HTTP_PORT" value="8080"/>""")
            appendLine("""    <property name="ZIDE.HTTPS_PORT" value="8443"/>""")
            appendLine("""    <property name="ZIDE.IAM_SERVER" value="https://accounts.csez.zohocorpin.com"/>""")
            appendLine("""    <property name="ZIDE.IAM_SERVICENAME" value="$serviceKey"/>""")
            appendLine("""    <property name="ZIDE.USER_NAME" value="$userName"/>""")
            appendLine("""    <property name="ZIDE.USER_MAIL" value="$userMail"/>""")
            appendLine("""    <property name="ZIDE.MACHINE_IP" value="$hostname"/>""")
            appendLine("""    <property name="ZIDE_DB_NAME" value=""/>""")
            appendLine("""    <property name="ZIDE.SCHEMA_NAME" value="jbossdb"/>""")
            appendLine("  </service>")
            appendLine("</services>")
        }
        propsXml.writeText(xml)
    }

    private fun resolveHostNameWithDomain(): String {
        val csezDomain = ".csez.zohocorpin.com"
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "localhost"
        }
        return if (hostname.endsWith(csezDomain)) hostname else "$hostname$csezDomain"
    }

    private fun writeRepositoryProperties(projectDir: File) {
        val zideResources = File(projectDir, ".zide_resources")
        zideResources.mkdirs()
        val repoProps = File(zideResources, "repository.properties")
        if (repoProps.exists()) return

        repoProps.writeText("repositorypath=${projectDir.absolutePath}\n")
    }

    private fun createZideBuildStructure(projectDir: File, deploymentDir: File, result: ZideProjectWizardDialog.WizardResult) {
        val zideResources = File(projectDir, ".zide_resources")

        val zideBuildDir = File(zideResources, "zide_build")
        if (!zideBuildDir.exists()) {
            zideBuildDir.mkdirs()
            val buildXml = File(zideBuildDir, "build.xml")
            if (!buildXml.exists()) {
                val serviceName = result.name
                val deploymentPath = File(deploymentDir, "AdventNet/Sas/tomcat").absolutePath
                buildXml.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<project name="zide-build-${serviceName}" default="postservicetarget" basedir=".">
    <target name="postservicetarget" description="Post-service deployment target">
        <echo message="Running post-service target for ${serviceName}"/>
        <echo message="Repository: ${projectDir.absolutePath}"/>
        <echo message="Deployment: ${deploymentPath}"/>
    </target>
</project>
""")
            }
        }

        val zideHookDir = File(zideResources, "zide_hook")
        if (!zideHookDir.exists()) {
            zideHookDir.mkdirs()
            val hookBuildXml = File(zideHookDir, "build.xml")
            if (!hookBuildXml.exists()) {
                val serviceName = result.name
                hookBuildXml.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<project name="zide-hook-${serviceName}" default="clone" basedir=".">
    <target name="clone" description="Hook dispatcher">
        <echo message="Running hook: ${'$'}{target} for ${serviceName}"/>
        <antcall target="${'$'}{target}"/>
    </target>
    <target name="precreationhook" description="Pre-creation hook">
        <echo message="Pre-creation hook for ${serviceName}"/>
    </target>
    <target name="postcreationhook" description="Post-creation hook">
        <echo message="Post-creation hook for ${serviceName}"/>
    </target>
    <target name="zidemodulehook" description="Zide module hook">
        <echo message="Zide module hook for ${serviceName}"/>
    </target>
</project>
""")
            }
        }
    }

    private fun runHooksIfAvailable(projectDir: File, deploymentDir: File, serviceName: String, indicator: ProgressIndicator) {
        val hookBuildXml = File(projectDir, ".zide_resources/zide_hook/build.xml")
        if (!hookBuildXml.exists()) return

        val antHome = com.zoho.dzide.deploysync.AntResolver.resolveAntHome(projectDir.absolutePath, null)
            ?: return
        val antExec = com.zoho.dzide.deploysync.AntResolver.resolveAntExecutable(antHome)
        val hookBaseDir = File(projectDir, ".zide_resources/zide_hook").absolutePath
        val deploymentPath = File(deploymentDir, "AdventNet/Sas/tomcat").absolutePath

        val hookTargets = listOf(
            "precreationhook" to "Running pre-creation hook...",
            "postcreationhook" to "Running post-creation hook...",
            "zidemodulehook" to "Running zide module hook..."
        )

        for ((target, message) in hookTargets) {
            indicator.text = message
            val hookResult = ProcessUtil.executeCapturing(
                command = com.zoho.dzide.util.ShellUtil.buildShellCommand(
                    "\"$antExec\"", "-f", "\"${hookBuildXml.absolutePath}\"",
                    "-Dbasedir=\"$hookBaseDir\"", "clone",
                    "-Dtarget=$target",
                    "-DREPOSITORY_PATH=${projectDir.absolutePath}",
                    "-DDEPLOYMENT_PATH=$deploymentPath",
                    "-DZIDE.PARENT_SERVICE=$serviceName"
                ),
                workingDir = projectDir.absolutePath,
                timeoutMs = 300_000
            )
            if (hookResult.exitCode != 0) {
                log.warn("Hook '$target' failed (exit code ${hookResult.exitCode}): ${hookResult.stderr}")
            }
        }
    }

    private fun showDeploymentProperties(projectDir: File, result: ZideProjectWizardDialog.WizardResult) {
        val projectPath = projectDir.absolutePath
        val zideConfig = ZideConfigParser.readZideConfig(projectPath) ?: return
        val properties = zideConfig.properties ?: return
        val serviceKey = properties.serviceKey

        val defaultProject = ProjectManager.getInstance().defaultProject
        val dialog = DeploymentPropertiesDialog(defaultProject, serviceKey, properties.properties, readOnly = false)
        if (dialog.showAndGet()) {
            val updated = dialog.getUpdatedProperties()
            val changed = updated.filter { (key, value) -> properties.properties[key] != value }
            if (changed.isNotEmpty()) {
                ZideConfigParser.writePropertiesToXml(projectPath, serviceKey, changed)
            }
        }
    }

    private fun patchDeploymentConfigs(projectDir: File, deploymentDir: File, result: ZideProjectWizardDialog.WizardResult) {
        val zideConfig = ZideConfigParser.readZideConfig(projectDir.absolutePath) ?: return
        val service = zideConfig.service ?: return
        val properties = zideConfig.properties ?: return

        val patchCtx = DeploymentConfigPatcher.buildPatchContext(
            serviceProps = service.properties,
            zideProps = properties.properties
        ) ?: return

        val patchResult = DeploymentConfigPatcher.patchAll(patchCtx)
        if (patchResult.errors.isNotEmpty()) {
            log.warn("Deployment config patching had errors: ${patchResult.errors.joinToString("; ")}")
        }
    }

    private fun rollback(projectDir: File, deploymentDir: File) {
        try {
            if (deploymentDir.exists()) {
                deploymentDir.deleteRecursively()
                log.info("Rollback: deleted deployment folder ${deploymentDir.absolutePath}")
            }
        } catch (e: Exception) {
            log.warn("Rollback: failed to delete deployment folder", e)
        }
        try {
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
                log.info("Rollback: deleted project folder ${projectDir.absolutePath}")
            }
        } catch (e: Exception) {
            log.warn("Rollback: failed to delete project folder", e)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
