package com.zoho.dzide.config.replacer

import com.intellij.openapi.diagnostic.Logger
import com.zoho.dzide.parser.ModuleZidePropsParser
import com.zoho.dzide.parser.PathResolver
import java.io.File
import java.nio.file.Path

/**
 * Applies product install.xml / install.properties replacements at launch
 * (Eclipse LaunchUtil.replace / ZideReplacer parity).
 */
object ConfigReplacerRunner {

    private val log = Logger.getInstance(ConfigReplacerRunner::class.java)

    data class Result(
        val applied: Boolean,
        val filesTouched: Int = 0,
        val messages: List<String> = emptyList()
    )

    fun run(
        projectPath: String,
        deploymentFolder: String,
        serviceProps: Map<String, String>,
        zideProps: Map<String, String>,
        branch: String
    ): Result {
        val projectName = serviceProps["ZIDE.PARENT_SERVICE"]?.takeIf { it.isNotBlank() }
            ?: serviceProps["ZIDE.SERVICE_KEY"].orEmpty()
        val replaceRoot = resolveReplaceRoot(projectPath, serviceProps, deploymentFolder)
        val props = LinkedHashMap<String, String>().apply {
            putAll(serviceProps)
            putAll(zideProps)
            putIfAbsent("ZIDE.DEPLOYMENT_FOLDER", deploymentFolder)
            putIfAbsent("ZIDE.REPOSITORY_PATH", projectPath)
            put("PROJECT_NAME", projectName)
        }

        val installXml = resolveInstallXml(projectPath, serviceProps)
        if (installXml != null && installXml.exists()) {
            return try {
                val parser = InstallXmlParser(installXml, props)
                val activeBranch = if (parser.isConfigurationExists(branch)) branch else "default"
                val fileRules = parser.parse(activeBranch)
                var touched = 0
                val messages = mutableListOf<String>()
                messages.add("install.xml: ${installXml.absolutePath}")
                messages.add("replace root: $replaceRoot")
                for ((key, changes) in fileRules) {
                    val colon = key.lastIndexOf(':')
                    if (colon < 0) continue
                    val relPath = key.substring(0, colon)
                    val type = key.substring(colon + 1).lowercase()
                    val target = File(replaceRoot, relPath.removePrefix("/").removePrefix(File.separator))
                    if (!target.exists()) {
                        messages.add("Skip missing file: ${target.absolutePath}")
                        continue
                    }
                    when {
                        type.startsWith("text") -> {
                            TextReplacer.replace(changes, target)
                            touched++
                            messages.add("text: ${target.name}")
                        }
                        type.startsWith("xml") -> {
                            XmlReplacer.replace(changes, target)
                            touched++
                            messages.add("xml: ${target.name}")
                        }
                        else -> messages.add("Unsupported type '$type' for ${target.name}")
                    }
                }
                // Only "applied" when files were actually changed — otherwise fall back to hardcoded patcher.
                Result(applied = touched > 0, filesTouched = touched, messages = messages)
            } catch (e: Exception) {
                log.warn("install.xml replace failed: ${e.message}", e)
                Result(applied = false, messages = listOf("install.xml error: ${e.message}"))
            }
        }

        val installProps = resolveInstallProperties(projectPath, serviceProps)
        if (installProps != null && installProps.exists()) {
            return try {
                val touched = InstallPropertiesReplacer.apply(installProps, replaceRoot, props)
                Result(applied = touched > 0, filesTouched = touched, messages = listOf("install.properties applied ($touched files)"))
            } catch (e: Exception) {
                log.warn("install.properties replace failed: ${e.message}", e)
                Result(applied = false, messages = listOf("install.properties error: ${e.message}"))
            }
        }

        return Result(applied = false)
    }

    internal fun resolveReplaceRoot(
        projectPath: String,
        serviceProps: Map<String, String>,
        deploymentFolder: String
    ): String {
        val moduleDir = serviceProps["ZIDE.REPOSITORY_MODULE_DIR"]
        val deployType = serviceProps["ZIDE.DEPLOY_TYPE"] ?: "M19"
        val zideRepo = PathResolver.resolveZideConfigRepoFromProject(projectPath)
        var serverHome: String? = null
        if (zideRepo != null && !moduleDir.isNullOrBlank()) {
            val propsPath = ModuleZidePropsParser.resolveModuleZidePropsPath(zideRepo, moduleDir, deployType)
            serverHome = ModuleZidePropsParser.readDeployFolderBasepath(propsPath)
        }
        return PathResolver.resolveTomcatHome(deploymentFolder, serverHome)
    }

    internal fun resolveInstallXml(projectPath: String, serviceProps: Map<String, String>): File? {
        val moduleDir = serviceProps["ZIDE.REPOSITORY_MODULE_DIR"]
        val deployType = serviceProps["ZIDE.DEPLOY_TYPE"] ?: "M19"
        val zideRepo = PathResolver.resolveZideConfigRepoFromProject(projectPath)
        val candidates = mutableListOf<File>()
        if (zideRepo != null && !moduleDir.isNullOrBlank()) {
            candidates.add(File(PathResolver.resolveModuleRecipeDir(zideRepo, moduleDir, deployType), "install.xml"))
        }
        candidates.add(Path.of(projectPath, ".zide_resources", "install.xml").toFile())
        return candidates.firstOrNull { it.exists() } ?: findFirst(projectPath, "install.xml")
    }

    internal fun resolveInstallProperties(projectPath: String, serviceProps: Map<String, String>): File? {
        val moduleDir = serviceProps["ZIDE.REPOSITORY_MODULE_DIR"]
        val deployType = serviceProps["ZIDE.DEPLOY_TYPE"] ?: "M19"
        val zideRepo = PathResolver.resolveZideConfigRepoFromProject(projectPath)
        val candidates = mutableListOf<File>()
        if (zideRepo != null && !moduleDir.isNullOrBlank()) {
            candidates.add(File(PathResolver.resolveModuleRecipeDir(zideRepo, moduleDir, deployType), "install.properties"))
        }
        candidates.add(Path.of(projectPath, ".zide_resources", "install.properties").toFile())
        return candidates.firstOrNull { it.exists() }
    }

    private fun findFirst(projectPath: String, name: String): File? {
        val root = File(projectPath)
        return root.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == name }
    }
}
