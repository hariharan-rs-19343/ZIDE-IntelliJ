package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class AppLogsAction : AnAction("App Logs", "Show application logs from server logs directory", null) {

    companion object {
        private const val MAX_TAIL_LINES = 5000
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val server = ServerActionUtil.getSelectedServer(e) ?: return
        val tomcatManager = TomcatManager.getInstance(project)

        tomcatManager.ensureToolWindow {
            val console = tomcatManager.appLogsConsoleView ?: return@ensureToolWindow

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAS-ZIDE")
            val appLogsContent = toolWindow?.contentManager?.findContent("App Logs")
            if (appLogsContent != null) {
                toolWindow.contentManager.setSelectedContent(appLogsContent)
            }

            val logsDir = Path.of(server.path).parent.resolve("logs")
            if (!logsDir.exists()) {
                NotificationUtil.error(project, "Logs directory not found: $logsDir")
                return@ensureToolWindow
            }

            val logFile = Files.list(logsDir).use { stream ->
                stream.filter { it.isRegularFile() && it.name.endsWith("application0.txt") }
                    .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                    .findFirst()
                    .orElse(null)
            }

            if (logFile == null) {
                NotificationUtil.error(project, "No *application0.txt log files found in $logsDir")
                return@ensureToolWindow
            }

            console.clear()
            console.print("=== Log file: $logFile (last $MAX_TAIL_LINES lines) ===\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val lines = readTailLines(logFile, MAX_TAIL_LINES)
                    for (line in lines) {
                        val contentType = when {
                            line.contains("ERROR") || line.contains("SEVERE") ->
                                ConsoleViewContentType.ERROR_OUTPUT
                            line.contains("WARN") ->
                                ConsoleViewContentType.LOG_WARNING_OUTPUT
                            else -> ConsoleViewContentType.NORMAL_OUTPUT
                        }
                        ConsoleUtil.print(console, project, "$line\n", contentType)
                    }
                } catch (ex: Exception) {
                    ConsoleUtil.print(console, project, "Error reading log file: ${ex.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }
    }

    private fun readTailLines(file: Path, maxLines: Int): List<String> {
        val fileSize = Files.size(file)
        if (fileSize == 0L) return emptyList()

        RandomAccessFile(file.toFile(), "r").use { raf ->
            val bufferSize = minOf(fileSize, 1024L * 1024L * 8L)
            val startPos = maxOf(0L, fileSize - bufferSize)
            raf.seek(startPos)
            val bytes = ByteArray((fileSize - startPos).toInt())
            raf.readFully(bytes)
            val content = String(bytes, Charsets.UTF_8)
            val allLines = content.lines()
            val dropFirst = startPos > 0
            val lines = if (dropFirst) allLines.drop(1) else allLines
            return if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
