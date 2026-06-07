package com.zoho.dzide.orchestrator

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

sealed class BuildUpdateStep(override val progress: Int, override val message: String) : OrchestratorStep {
    data object StopServer : BuildUpdateStep(1, "Stopping server...")
    data object CopyBuildZip : BuildUpdateStep(10, "Copying build zip...")
    data object ExtractBuildZip : BuildUpdateStep(20, "Extracting build zip...")
    data object ProcessWarFiles : BuildUpdateStep(50, "Processing WAR files...")
    data object ExecutePreCreateHook : BuildUpdateStep(65, "Running pre-creation hook...")
    data object ExecutePostCreateHook : BuildUpdateStep(75, "Running post-creation hook...")
    data object ExecuteZideModuleHook : BuildUpdateStep(85, "Running ZIDE module hook...")
    data object PatchConfigs : BuildUpdateStep(92, "Patching deployment configs...")
    data object Complete : BuildUpdateStep(100, "Build update complete")
}

class BuildUpdateOrchestrator(
    private val buildZipPath: String,
    private val deploymentPath: String,
    private val parentService: String,
    private val onStopServer: () -> Boolean,
    private val onRunHook: (target: String, useZideHookDir: Boolean) -> Boolean,
    private val onPatchConfigs: () -> Unit,
) : Action {

    private val log = Logger.getInstance(BuildUpdateOrchestrator::class.java)

    override fun execute(progressReporter: ProgressReporter?): ActionResult {
        return try {
            progressReporter.runStep(BuildUpdateStep.StopServer) {
                onStopServer()
            }?.let { return it }

            val tomcatPath = "$deploymentPath/AdventNet/Sas/tomcat"

            progressReporter.runStep(BuildUpdateStep.CopyBuildZip) {
                copyBuildZip(buildZipPath, deploymentPath)
            }?.let { return it }

            progressReporter.runStep(BuildUpdateStep.ExtractBuildZip) {
                extractBuildZip(deploymentPath)
            }?.let { return it }

            progressReporter.runStep(BuildUpdateStep.ProcessWarFiles) {
                processWarFiles(tomcatPath)
            }?.let { return it }

            progressReporter.runStep(BuildUpdateStep.ExecutePreCreateHook) {
                onRunHook("precreationhook", true)
            }

            progressReporter.runStep(BuildUpdateStep.ExecutePostCreateHook) {
                onRunHook("postcreationhook", false)
            }

            progressReporter.runStep(BuildUpdateStep.ExecuteZideModuleHook) {
                onRunHook("zidemodulehook", true)
            }

            progressReporter.runStep(BuildUpdateStep.PatchConfigs) {
                onPatchConfigs()
                true
            }

            progressReporter?.reportComplete()
            ActionResult.Success
        } catch (e: Exception) {
            log.error("[BuildUpdate] Failed", e)
            progressReporter?.reportError("Build update failed: ${e.message}")
            ActionResult.Failure("Build update failed", e)
        }
    }

    private fun copyBuildZip(zipPath: String, destDir: String): Boolean {
        val source = Path.of(zipPath)
        val dest = Path.of(destDir, source.fileName.toString())
        Files.createDirectories(Path.of(destDir))
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
        log.info("[BuildUpdate] Copied ${source.fileName} to $destDir")
        return true
    }

    private fun extractBuildZip(deploymentDir: String): Boolean {
        val zipFiles = File(deploymentDir).listFiles { f -> f.extension == "zip" } ?: return false
        val zipFile = zipFiles.firstOrNull() ?: return false
        unzip(zipFile, File(deploymentDir))
        zipFile.delete()
        log.info("[BuildUpdate] Extracted and deleted ${zipFile.name}")
        return true
    }

    private fun processWarFiles(tomcatPath: String): Boolean {
        val webappsDir = File(tomcatPath, "webapps")
        if (!webappsDir.isDirectory) return true

        val warFiles = webappsDir.listFiles { f -> f.extension == "war" } ?: return true
        for (war in warFiles) {
            val targetName = if (war.nameWithoutExtension.equals("ROOT", ignoreCase = true)) {
                parentService
            } else {
                war.nameWithoutExtension
            }
            val targetDir = File(webappsDir, targetName)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            unzip(war, targetDir)
            war.delete()
            log.info("[BuildUpdate] Extracted WAR ${war.name} as $targetName")
        }
        return true
    }

    private fun unzip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
