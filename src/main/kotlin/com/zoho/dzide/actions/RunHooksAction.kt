package com.zoho.dzide.actions

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.zoho.dzide.deploysync.AntResolver
import com.zoho.dzide.tomcat.TomcatManager
import com.zoho.dzide.util.ConsoleUtil
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.util.ProcessUtil
import com.zoho.dzide.util.ShellUtil
import com.zoho.dzide.zide.ZideConfigParser
import java.io.File
import java.net.InetAddress
import java.nio.file.Path

object RunHooksAction {

    private val log = Logger.getInstance(RunHooksAction::class.java)

    data class HookDef(
        val target: String,
        val hookName: String,
        val useZideHookDir: Boolean,
        val label: String
    )

    val PRECREATION = HookDef("precreationhook", "precreation", true, "Precreation Hook")
    val POSTCREATION = HookDef("postcreationhook", "postcreation", false, "Postcreation Hook")
    val ZIDEMODULE = HookDef("zidemodulehook", "zideoperations", true, "ZideModule Hook")

    private fun resolveContext(project: Project): HookContext? {
        val projectPath = project.basePath ?: return null
        val zideConfig = ZideConfigParser.readZideConfig(projectPath)
        val service = zideConfig?.service
        val deploymentFolder = service?.properties?.get("ZIDE.DEPLOYMENT_FOLDER")
        val parentService = service?.properties?.get("ZIDE.PARENT_SERVICE") ?: project.name

        val repositoryPath = UpdateDeploymentAction.readRepositoryPath(projectPath) ?: projectPath
        val deploymentPath = if (deploymentFolder != null) {
            Path.of(deploymentFolder, "AdventNet", "Sas", "tomcat").toString()
        } else null

        val antHome = AntResolver.resolveAntHome(projectPath, null)
        if (antHome == null) {
            NotificationUtil.error(project, "ANT_HOME not found. Configure ANT in your environment.")
            return null
        }
        val antExec = AntResolver.resolveAntExecutable(antHome)

        return HookContext(projectPath, repositoryPath, deploymentPath, parentService, antExec)
    }

    data class HookContext(
        val projectPath: String,
        val repositoryPath: String,
        val deploymentPath: String?,
        val parentService: String,
        val antExec: String
    )

    private val REQUIRED_PROPERTIES = mapOf(
        "ZIDE.HOST_NAME" to { resolveHostNameWithDomain() },
        "ZIDE.USER_MAIL" to { "${System.getProperty("user.name", "")}@zohocorp.com" },
        "ZIDE.IAM_SERVER" to { "https://accounts.csez.zohocorpin.com" },
        "ZIDE.HTTP_PORT" to { "8080" },
        "ZIDE.HTTPS_PORT" to { "8443" },
        "ZIDE.IAM_SERVICENAME" to { "" },
        "ZIDE.USER_NAME" to { System.getProperty("user.name", "") },
        "ZIDE.MACHINE_IP" to { resolveHostNameWithDomain() },
        "ZIDE_DB_TYPE" to { "PGSQL" },
        "ZIDE_DB_HOST" to { "localhost" },
        "ZIDE_DB_USER" to { "root" },
        "ZIDE_DB_PASS" to { "" },
        "ZIDE_DB_NAME" to { "" },
        "ZIDE.SCHEMA_NAME" to { "jbossdb" }
    )

    private fun resolveHostNameWithDomain(): String {
        val csezDomain = ".csez.zohocorpin.com"
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "localhost"
        }
        return if (hostname.endsWith(csezDomain)) hostname else "$hostname$csezDomain"
    }

    private fun ensureRequiredProperties(projectPath: String, console: ConsoleView, project: Project): String? {
        ZideConfigParser.clearCache(projectPath)
        val zideConfig = ZideConfigParser.readZideConfig(projectPath)
        val properties = zideConfig?.properties
        val serviceKey = properties?.serviceKey

        if (serviceKey == null) {
            ConsoleUtil.print(console, project,
                "  Warning: Could not read zide_properties.xml, skipping property validation.\n",
                ConsoleViewContentType.LOG_WARNING_OUTPUT)
            return null
        }

        val existingProps = properties.properties
        val missing = mutableMapOf<String, String>()

        for ((key, defaultProvider) in REQUIRED_PROPERTIES) {
            if (!existingProps.containsKey(key)) {
                val defaultValue = if (key == "ZIDE.IAM_SERVICENAME") serviceKey else defaultProvider()
                missing[key] = defaultValue
            }
        }

        if (missing.isNotEmpty()) {
            ConsoleUtil.print(console, project,
                "  Validating zide_properties.xml: inserting ${missing.size} missing key(s)...\n",
                ConsoleViewContentType.SYSTEM_OUTPUT)
            for ((key, value) in missing) {
                val display = if (value.isBlank()) "(empty)" else value
                ConsoleUtil.print(console, project,
                    "    + $key = $display\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            ZideConfigParser.writePropertiesToXml(projectPath, serviceKey, missing)
            ConsoleUtil.print(console, project,
                "  zide_properties.xml updated.\n\n",
                ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        return serviceKey
    }

    private fun runHooks(project: Project, hooks: List<HookDef>, taskTitle: String) {
        val ctx = resolveContext(project) ?: return

        val manager = TomcatManager.getInstance(project)
        manager.ensureToolWindow {
            val console = manager.consoleView ?: return@ensureToolWindow

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle, true) {
                override fun run(indicator: ProgressIndicator) {
                    ConsoleUtil.print(console, project, "\n=== $taskTitle ===\n", ConsoleViewContentType.SYSTEM_OUTPUT)

                    indicator.text = "Validating properties..."
                    indicator.fraction = 0.0
                    ensureRequiredProperties(ctx.projectPath, console, project)

                    for ((i, hook) in hooks.withIndex()) {
                        indicator.text = "Running ${hook.label}..."
                        indicator.fraction = i.toDouble() / hooks.size

                        val baseDir = if (hook.useZideHookDir) {
                            Path.of(ctx.projectPath, ".zide_resources", "zide_hook").toString()
                        } else {
                            Path.of(ctx.projectPath, ".zide_resources", "zide_build").toString()
                        }
                        val buildXml = Path.of(baseDir, "build.xml").toString()

                        if (!File(buildXml).exists()) {
                            ConsoleUtil.print(console, project,
                                "  Skipping ${hook.label}: ${buildXml} not found.\n",
                                ConsoleViewContentType.LOG_WARNING_OUTPUT)
                            continue
                        }

                        ConsoleUtil.print(console, project,
                            "\n--- ${hook.label} (${hook.target}) ---\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT)

                        val success = UpdateDeploymentAction.runAntHook(
                            console = console,
                            project = project,
                            antExec = ctx.antExec,
                            buildXml = buildXml,
                            buildBaseDir = baseDir,
                            target = hook.target,
                            repositoryPath = ctx.repositoryPath,
                            deploymentPath = ctx.deploymentPath ?: "",
                            parentService = ctx.parentService,
                            extraProps = emptyMap()
                        )

                        val outputFile = File(baseDir, "output_${hook.hookName}.txt")
                        try {
                            outputFile.writeText("Hook executed via IntelliJ Run Hooks action\n")
                        } catch (_: Exception) {}

                        if (success) {
                            ConsoleUtil.print(console, project,
                                "  ${hook.label} completed successfully.\n",
                                ConsoleViewContentType.SYSTEM_OUTPUT)
                        } else {
                            ConsoleUtil.print(console, project,
                                "  ${hook.label} FAILED.\n",
                                ConsoleViewContentType.ERROR_OUTPUT)
                        }
                    }

                    indicator.fraction = 1.0
                    ConsoleUtil.print(console, project, "\n=== Hooks completed ===\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    NotificationUtil.info(project, "$taskTitle completed.")
                }
            })
        }
    }

    class RunAllHooks : AnAction("Run All Hooks", "Run all ZIDE hooks", null) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            runHooks(project, listOf(PRECREATION, POSTCREATION, ZIDEMODULE), "Run All Hooks")
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project?.basePath != null
        }
    }

    class RunPrecreationHook : AnAction("Run Precreation Hook", "Run precreationhook from zide_hook/", null) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            runHooks(project, listOf(PRECREATION), "Run Precreation Hook")
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project?.basePath != null
        }
    }

    class RunPostcreationHook : AnAction("Run Postcreation Hook", "Run postcreationhook from zide_build/", null) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            runHooks(project, listOf(POSTCREATION), "Run Postcreation Hook")
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project?.basePath != null
        }
    }

    class RunZideModuleHook : AnAction("Run ZideModule Hook", "Run zidemodulehook from zide_hook/", null) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            runHooks(project, listOf(ZIDEMODULE), "Run ZideModule Hook")
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project?.basePath != null
        }
    }
}
