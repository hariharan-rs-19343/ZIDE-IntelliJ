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
        val messages: List<String> = emptyList(),
        val missingDatabaseName: Boolean = false
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

        val dbName = zideProps["ZIDE_DB_NAME"]?.trim()?.ifEmpty { null }
            ?: zideProps["ZIDE.DB_NAME"]?.trim()?.ifEmpty { null }
        val missingDatabaseName = dbName == null

        val messages = mutableListOf<String>()
        var touched = 0

        val installXml = resolveInstallXml(projectPath, serviceProps)
        if (installXml != null && installXml.exists()) {
            try {
                messages.add("install.xml: ${installXml.absolutePath}")
                messages.add("replace root: $replaceRoot")
                touched += applyInstallXml(installXml, props, branch, replaceRoot, messages)
            } catch (e: Exception) {
                log.warn("install.xml replace failed: ${e.message}", e)
                messages.add("install.xml error: ${e.message}")
            }
        } else {
            val installProps = resolveInstallProperties(projectPath, serviceProps)
            if (installProps != null && installProps.exists()) {
                try {
                    val count = InstallPropertiesReplacer.apply(installProps, replaceRoot, props)
                    touched += count
                    messages.add("install.properties applied ($count files)")
                } catch (e: Exception) {
                    log.warn("install.properties replace failed: ${e.message}", e)
                    messages.add("install.properties error: ${e.message}")
                }
            }
        }

        if (!missingDatabaseName) {
            val dbXml = resolveDbReplaceXml(projectPath, serviceProps, zideProps)
            if (dbXml != null && dbXml.exists()) {
                try {
                    messages.add("db recipe: ${dbXml.absolutePath}")
                    touched += applyInstallXml(dbXml, props, "default", replaceRoot, messages)
                } catch (e: Exception) {
                    log.warn("db recipe replace failed: ${e.message}", e)
                    messages.add("db recipe error: ${e.message}")
                }
            }
        }

        return Result(
            applied = touched > 0,
            filesTouched = touched,
            messages = messages,
            missingDatabaseName = missingDatabaseName
        )
    }

    private fun applyInstallXml(
        installXml: File,
        props: Map<String, String>,
        branch: String,
        replaceRoot: String,
        messages: MutableList<String>
    ): Int {
        val parser = InstallXmlParser(installXml, props)
        val activeBranch = if (parser.isConfigurationExists(branch)) branch else "default"
        val fileRules = parser.parse(activeBranch)
        var touched = 0
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
        return touched
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

    /**
     * Eclipse AbstractZideDBUtil.replaceDBProperties: module recipe first, then
     * `{zide}/deployment/pgsql_replace.xml` or `mysql_replace.xml`.
     */
    internal fun resolveDbReplaceXml(
        projectPath: String,
        serviceProps: Map<String, String>,
        zideProps: Map<String, String>
    ): File? {
        val dbType = zideProps["ZIDE_DB_TYPE"]?.trim().orEmpty()
        val fileName = if (dbType.equals("PGSQL", ignoreCase = true)) "pgsql_replace.xml" else "mysql_replace.xml"
        val moduleDir = serviceProps["ZIDE.REPOSITORY_MODULE_DIR"]
        val deployType = serviceProps["ZIDE.DEPLOY_TYPE"] ?: "M19"
        val zideRepo = PathResolver.resolveZideConfigRepoFromProject(projectPath)
        val candidates = mutableListOf<File>()
        if (zideRepo != null && !moduleDir.isNullOrBlank()) {
            candidates.add(File(PathResolver.resolveModuleRecipeDir(zideRepo, moduleDir, deployType), fileName))
        }
        if (zideRepo != null) {
            candidates.add(File(Path.of(zideRepo, "deployment", fileName).toString()))
        }
        return candidates.firstOrNull { it.exists() }
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
