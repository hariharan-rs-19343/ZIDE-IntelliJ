package com.zoho.dzide.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PluginUpdateChecker {

    private val log = Logger.getInstance(PluginUpdateChecker::class.java)
    private const val PLUGIN_ID = "com.zoho.intellij.zide"
    private const val GITHUB_API_URL = "https://api.github.com/repos/hariharan-rs-19343/ZIDE-IntelliJ/releases/latest"

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    fun getCurrentVersion(): String {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        return descriptor?.version ?: "0.0.0"
    }

    fun checkForUpdate(): UpdateInfo? {
        try {
            val conn = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                log.info("GitHub API returned ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val tagName = extractJsonString(responseBody, "tag_name") ?: return null
            val downloadUrl = extractAssetDownloadUrl(responseBody) ?: return null
            val body = extractJsonString(responseBody, "body") ?: ""

            val latestVersion = tagName.removePrefix("V").removePrefix("v")
            val currentVersion = getCurrentVersion()

            if (!isNewerVersion(latestVersion, currentVersion)) {
                log.info("Plugin is up to date: $currentVersion (latest: $latestVersion)")
                return null
            }

            return UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = body
            )
        } catch (ex: Exception) {
            log.info("Update check failed: ${ex.message}")
            return null
        }
    }

    fun showUpdateNotification(project: Project?, updateInfo: UpdateInfo) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("DZIDE Notifications")
            .createNotification(
                "ZIDE Update Available",
                "Version ${updateInfo.latestVersion} is available (current: ${updateInfo.currentVersion}).",
                NotificationType.INFORMATION
            )

        notification.addAction(NotificationAction.createSimpleExpiring("Update Now") {
            downloadAndInstall(project, updateInfo)
        })

        notification.addAction(NotificationAction.createSimpleExpiring("Later") {
            notification.expire()
        })

        notification.notify(project)
    }

    fun downloadAndInstall(project: Project?, updateInfo: UpdateInfo) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating ZIDE Plugin", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Downloading ZIDE v${updateInfo.latestVersion}..."
                indicator.isIndeterminate = true

                try {
                    val tempFile = Files.createTempFile("zide-update-", ".zip")
                    val conn = URL(updateInfo.downloadUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000

                    conn.inputStream.use { input ->
                        Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    conn.disconnect()

                    indicator.text = "Installing update..."

                    val pluginsDir = File(PathManager.getPluginsPath())
                    val existingPlugin = File(pluginsDir, "zide-intelliJ-plugin")
                    if (existingPlugin.exists()) {
                        existingPlugin.deleteRecursively()
                    }

                    val targetZip = File(pluginsDir, "zide-intelliJ-plugin-${updateInfo.latestVersion}.zip")
                    Files.copy(tempFile, targetZip.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.deleteIfExists(tempFile)

                    // Extract zip into plugins directory
                    val unzipResult = com.zoho.dzide.util.ProcessUtil.executeCapturing(
                        command = listOf("unzip", "-o", targetZip.absolutePath, "-d", pluginsDir.absolutePath),
                        timeoutMs = 60_000
                    )
                    targetZip.delete()

                    ApplicationManager.getApplication().invokeLater {
                        if (unzipResult.exitCode == 0) {
                            val restartNotification = NotificationGroupManager.getInstance()
                                .getNotificationGroup("DZIDE Notifications")
                                .createNotification(
                                    "ZIDE Updated",
                                    "ZIDE v${updateInfo.latestVersion} installed. Restart IDE to apply changes.",
                                    NotificationType.INFORMATION
                                )

                            restartNotification.addAction(NotificationAction.createSimpleExpiring("Restart Now") {
                                ApplicationManagerEx.getApplicationEx().restart(true)
                            })

                            restartNotification.addAction(NotificationAction.createSimpleExpiring("Later") {
                                restartNotification.expire()
                            })

                            restartNotification.notify(project)
                        } else {
                            installManualFallback(project, tempFile.toFile(), updateInfo)
                        }
                    }
                } catch (ex: Exception) {
                    log.error("Failed to download plugin update", ex)
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("DZIDE Notifications")
                            .createNotification(
                                "Update Failed",
                                "Failed to download ZIDE update: ${ex.message}",
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                }
            }
        })
    }

    private fun installManualFallback(project: Project?, zipFile: File, updateInfo: UpdateInfo) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("DZIDE Notifications")
            .createNotification(
                "ZIDE v${updateInfo.latestVersion} Downloaded",
                "Auto-install failed. Install manually from: ${zipFile.absolutePath}\n(Settings > Plugins > Install from Disk)",
                NotificationType.WARNING
            )
        notification.notify(project)
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractAssetDownloadUrl(json: String): String? {
        val assetsMatch = Regex(""""assets"\s*:\s*\[""").find(json) ?: return null
        val afterAssets = json.substring(assetsMatch.range.last)
        val urlRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+\.zip)"""")
        return urlRegex.find(afterAssets)?.groupValues?.get(1)
    }
}
