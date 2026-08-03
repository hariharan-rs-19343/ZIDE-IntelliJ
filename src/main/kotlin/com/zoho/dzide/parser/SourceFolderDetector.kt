package com.zoho.dzide.parser

import java.io.File

/**
 * Detects Java source roots under a project directory (VS Code / Eclipse ZIDE parity).
 */
object SourceFolderDetector {

    private val CANDIDATES = listOf(
        "src/main/java",
        "src",
        "source",
        "sources",
        "java"
    )

    /**
     * Returns relative source folder paths that exist on disk.
     * Prefer more specific paths first; skip a parent when a nested candidate is already selected
     * (e.g. keep `src/main/java` and skip bare `src` if both exist).
     */
    fun detect(projectDir: File): List<String> {
        val found = CANDIDATES.filter { File(projectDir, it).isDirectory }
        if (found.isEmpty()) return emptyList()

        val selected = mutableListOf<String>()
        for (path in found) {
            val coveredByMoreSpecific = selected.any { selectedPath ->
                selectedPath.startsWith("$path/")
            }
            if (!coveredByMoreSpecific) {
                selected.removeAll { path.startsWith("$it/") }
                selected.add(path)
            }
        }
        return selected
    }

    fun detectCsv(projectDir: File): String =
        detect(projectDir).joinToString(",").ifEmpty { "src/main/java" }

    fun fromCsv(raw: String?): List<String> =
        raw?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}
