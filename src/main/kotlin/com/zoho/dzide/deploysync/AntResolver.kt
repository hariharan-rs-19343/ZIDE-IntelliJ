package com.zoho.dzide.deploysync

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

object AntResolver {

    fun resolveAntExecutable(antHome: String): String {
        val execName = if (com.zoho.dzide.util.ShellUtil.isWindows) "ant.bat" else "ant"
        return Path.of(antHome, "bin", execName).toString()
    }

    private fun isValidAntHome(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return Path.of(resolveAntExecutable(candidate)).exists()
    }

    private fun detectAntHomeFromEnvironment(): String? {
        val antHome = System.getenv("ANT_HOME")
        return if (isValidAntHome(antHome)) antHome else null
    }

    private fun detectAntHomeFromShellRc(): String? {
        val rcFiles = listOf(
            File(System.getProperty("user.home"), ".zshrc"),
            File(System.getProperty("user.home"), ".bashrc"),
            File(System.getProperty("user.home"), ".bash_profile")
        )
        for (rcFile in rcFiles) {
            if (!rcFile.exists()) continue
            rcFile.readLines().forEach { line ->
                val trimmed = line.trim()
                // Match: export ANT_HOME=... or ANT_HOME=...
                val match = Regex("""^(?:export\s+)?ANT_HOME\s*=\s*["']?(.+?)["']?\s*$""").find(trimmed)
                if (match != null) {
                    val value = match.groupValues[1]
                    if (isValidAntHome(value)) return value
                }
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveAntHome(projectPath: String, persistedAntHome: String?): String? {
        if (isValidAntHome(persistedAntHome)) return persistedAntHome
        detectAntHomeFromEnvironment()?.let { return it }
        detectAntHomeFromShellRc()?.let { return it }
        return null
    }
}
