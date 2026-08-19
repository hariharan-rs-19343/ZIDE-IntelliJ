package com.zoho.dzide.parser

import com.zoho.dzide.model.AutoCopyMapping
import com.zoho.dzide.model.HookTaskMapping
import com.zoho.dzide.model.ModuleZidePropsData
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream

object ModuleZidePropsParser {

    private data class CacheEntry(
        val mtimeMs: Long,
        val data: ModuleZidePropsData
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun resolveModuleZidePropsPath(
        zideFolderPath: String,
        repositoryModuleDir: String,
        deployType: String = "M19"
    ): String {
        return Path.of(zideFolderPath, "deployment", repositoryModuleDir, deployType, "Zide.properties").toString()
    }

    fun parseJavaProperties(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) continue
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) continue
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            result[key] = value
        }
        return result
    }

    fun readModuleZidePropsDataFromFile(zidePropertiesPath: String?): ModuleZidePropsData {
        if (zidePropertiesPath == null) return ModuleZidePropsData()
        val file = Path.of(zidePropertiesPath)
        if (!file.exists()) return ModuleZidePropsData()

        return try {
            val mtimeMs = file.getLastModifiedTime().toMillis()
            val cached = cache[zidePropertiesPath]
            if (cached != null && cached.mtimeMs == mtimeMs) return cached.data

            val content = file.inputStream().bufferedReader().readText()
            val properties = parseJavaProperties(content)
            val data = ModuleZidePropsData(
                zidePropertiesPath = zidePropertiesPath,
                launchVmArguments = properties["launch.vmarguments"]?.trim()?.ifEmpty { null },
                hookTasksRaw = properties["hooks.resourcemodify.all.calltasks"]?.trim()?.ifEmpty { null },
                autoResourceCopyRaw = properties["deploy.autoresource.copy"]?.trim()?.ifEmpty { null }
            )
            cache[zidePropertiesPath] = CacheEntry(mtimeMs, data)
            data
        } catch (_: Exception) {
            ModuleZidePropsData()
        }
    }

    fun readAllPropertiesFromFile(zidePropertiesPath: String?): Map<String, String> {
        if (zidePropertiesPath == null) return emptyMap()
        val file = Path.of(zidePropertiesPath)
        if (!file.exists()) return emptyMap()
        return try {
            parseJavaProperties(file.inputStream().bufferedReader().readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun readDeployFolderBasepath(zidePropertiesPath: String?): String? {
        val value = readAllPropertiesFromFile(zidePropertiesPath)["deploy.folder.basepath"]?.trim()
        return value?.ifEmpty { null }
    }

    fun readLaunchVmArgumentsFromProperties(zidePropertiesPath: String?): String? =
        readModuleZidePropsDataFromFile(zidePropertiesPath).launchVmArguments

    fun resolveModuleZidePropsFromZideFolder(
        zideFolderPath: String,
        repositoryModuleDir: String,
        deployType: String = "M19"
    ): ModuleZidePropsData? {
        if (zideFolderPath.isBlank() || repositoryModuleDir.isBlank()) return null
        val propsPath = resolveModuleZidePropsPath(zideFolderPath, repositoryModuleDir, deployType)
        if (!Path.of(propsPath).exists()) return null
        val data = readModuleZidePropsDataFromFile(propsPath)
        return data.copy(zideFolderPath = zideFolderPath)
    }

    private fun normalizeResourceFolder(value: String, projectName: String): String {
        val trimmed = value.trim().trimStart('/').trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val normalizedProject = projectName.trim()
        if (normalizedProject.isNotEmpty() &&
            (trimmed == normalizedProject || trimmed.startsWith("$normalizedProject/"))
        ) {
            return trimmed.substring(normalizedProject.length).trimStart('/')
        }
        return trimmed
    }

    fun parseHookTaskMappings(rawValue: String?, projectName: String): List<HookTaskMapping> {
        if (rawValue.isNullOrBlank()) return emptyList()
        return rawValue.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0 || separator >= entry.length - 1) return@mapNotNull null
                val folder = normalizeResourceFolder(entry.substring(0, separator), projectName)
                val antTarget = entry.substring(separator + 1).trim()
                if (folder.isEmpty() || antTarget.isEmpty()) return@mapNotNull null
                HookTaskMapping(folder, antTarget)
            }
    }

    fun parseAutoCopyMappings(rawValue: String?, projectName: String): List<AutoCopyMapping> {
        if (rawValue.isNullOrBlank()) return emptyList()
        return rawValue.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0 || separator >= entry.length - 1) return@mapNotNull null
                val sourcePath = normalizeResourceFolder(entry.substring(0, separator), projectName)
                val destTemplate = entry.substring(separator + 1).trim()
                if (sourcePath.isEmpty() || destTemplate.isEmpty()) return@mapNotNull null
                AutoCopyMapping(sourcePath, destTemplate)
            }
    }

    fun clearCache(zidePropertiesPath: String? = null) {
        if (zidePropertiesPath != null) {
            cache.remove(zidePropertiesPath)
        } else {
            cache.clear()
        }
    }
}
