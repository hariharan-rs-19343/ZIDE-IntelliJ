package com.zoho.dzide.util

import com.intellij.util.EnvironmentUtil
import com.zoho.dzide.settings.ZideSettingsState
import java.io.File

/**
 * Custom protoc for ANT builds (Settings > Tools > Zide > "Use custom protoc").
 * Passed as `-Dprotobuf_local_dir` / `-Dprotobuf_dir` (overrides build/ant.properties) and prepended to PATH.
 */
object ProtocConfig {

    private val executableNames = listOf("protoc", "protoc.exe")

    /** Folder containing protoc, from either the protoc executable or that folder; null when invalid. */
    fun binDir(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        if (file.isFile && file.name in executableNames) return file.absoluteFile.parent
        if (file.isDirectory && executableNames.any { File(file, it).isFile }) return file.absolutePath
        return null
    }

    fun isEnabled(): Boolean = ZideSettingsState.getInstance().protocEnabled

    /** Protoc folder when the setting is on and the path is valid; null otherwise. */
    fun activeBinDir(): String? {
        val settings = ZideSettingsState.getInstance()
        return if (settings.protocEnabled) binDir(settings.protocPath) else null
    }

    fun antArgs(dir: String): List<String> = listOf("-Dprotobuf_local_dir=$dir", "-Dprotobuf_dir=$dir")

    fun withPath(env: Map<String, String>, dir: String): Map<String, String> {
        val current = env["PATH"] ?: EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        return env + ("PATH" to if (current.isEmpty()) dir else "$dir${File.pathSeparator}$current")
    }
}
