package com.zoho.dzide.deploysync

import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Finds the zip produced by the product ANT build (`{project}/build/`).
 *
 * Order, each candidate only if modified at/after the build start (never deploy a stale zip):
 * 1. `build/ant.properties` → `${output_dir}/${product_name}.zip`, then newest zip in `output_dir`
 * 2. service.xml `ZIDE.DOWNLOAD_URL` when it is a local path (zip file, or newest zip in that folder)
 * 3. Zip saved from an earlier file-chooser pick
 */
object BuildOutputLocator {

    const val SOURCE_ANT_PROPERTIES = "build/ant.properties"
    const val SOURCE_DOWNLOAD_URL = "service.xml ZIDE.DOWNLOAD_URL"
    const val SOURCE_SAVED = "saved preference"

    private val reference = Regex("""\$\{([^}]+)}""")

    /** Properties with surrounding quotes stripped and `${key}` references resolved; `basedir` = [buildDir]. */
    fun parseAntProperties(text: String, buildDir: Path): Map<String, String> {
        val props = Properties()
        try {
            props.load(StringReader(text))
        } catch (_: Exception) {
            return emptyMap()
        }
        val values = props.stringPropertyNames().associateWith { unquote(props.getProperty(it)) }.toMutableMap()
        values.putIfAbsent("basedir", buildDir.toString())
        repeat(10) {
            var changed = false
            for ((key, value) in values.toMap()) {
                val resolved = reference.replace(value) { m -> values[m.groupValues[1]] ?: m.value }
                if (resolved != value) {
                    values[key] = resolved
                    changed = true
                }
            }
            if (!changed) return values
        }
        return values
    }

    private fun unquote(value: String): String {
        val v = value.trim()
        return if (v.length >= 2 && v.first() == '"' && v.last() == '"') v.substring(1, v.length - 1) else v
    }

    private fun readAntProperties(buildDir: Path): Map<String, String> {
        val file = buildDir.resolve("ant.properties")
        if (!file.isRegularFile()) return emptyMap()
        return try {
            parseAntProperties(Files.readString(file), buildDir)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** `output_dir` from build/ant.properties, when it is fully resolved. */
    fun outputDir(buildDir: Path): Path? =
        readAntProperties(buildDir)["output_dir"]?.takeUnless { it.contains("\${") }?.let { Path.of(it) }

    fun expectedZip(buildDir: Path): Path? {
        val props = readAntProperties(buildDir)
        val outputDir = props["output_dir"] ?: return null
        val productName = props["product_name"] ?: return null
        if (outputDir.contains("\${") || productName.contains("\${")) return null
        return Path.of(outputDir, "$productName.zip")
    }

    fun find(buildDir: Path, downloadUrl: String?, savedPath: String?, sinceMillis: Long): Pair<Path, String>? {
        expectedZip(buildDir)?.takeIf { isFresh(it, sinceMillis) }?.let { return it to SOURCE_ANT_PROPERTIES }
        outputDir(buildDir)?.let { newestZip(it, sinceMillis) }?.let { return it to SOURCE_ANT_PROPERTIES }

        localPath(downloadUrl)?.let { path ->
            val found = if (path.isDirectory()) newestZip(path, sinceMillis) else path.takeIf { isFresh(it, sinceMillis) }
            found?.let { return it to SOURCE_DOWNLOAD_URL }
        }

        localPath(savedPath)?.takeIf { isFresh(it, sinceMillis) }?.let { return it to SOURCE_SAVED }
        return null
    }

    /** Local filesystem path, or null for blank values and http(s)/ftp URLs. */
    private fun localPath(value: String?): Path? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(v) && !v.startsWith("file://")) return null
        return try {
            Path.of(v.removePrefix("file://"))
        } catch (_: Exception) {
            null
        }
    }

    private fun isFresh(path: Path, sinceMillis: Long): Boolean =
        path.isRegularFile() && path.extension.equals("zip", ignoreCase = true) &&
            File(path.toString()).lastModified() >= sinceMillis

    private fun newestZip(dir: Path, sinceMillis: Long): Path? {
        if (!dir.isDirectory()) return null
        return dir.toFile().listFiles()
            ?.map { it.toPath() }
            ?.filter { isFresh(it, sinceMillis) }
            ?.maxByOrNull { it.toFile().lastModified() }
    }
}
