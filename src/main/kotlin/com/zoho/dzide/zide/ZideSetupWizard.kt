package com.zoho.dzide.zide

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.parser.ModuleZidePropsParser
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.util.NotificationUtil
import java.nio.file.Path
import kotlin.io.path.exists

object ZideSetupWizard {

    fun runZideSetupWizard(project: Project, projectPath: String): TomcatServer? {
        val zideConfig = ZideConfigParser.readZideConfig(projectPath) ?: return null

        val (services, service, properties, serviceOptions) = zideConfig
        if (service == null || properties == null) {
            NotificationUtil.error(project, "Failed to parse ZIDE configuration files.")
            return null
        }

        var selectedService = service
        if (serviceOptions.size > 1) {
            val selectedKey = showServiceSelector(project, serviceOptions) ?: return null
            selectedService = services.find { it.key == selectedKey }
                ?: services.find { it.key == "ROOT" }
                ?: service
        }

        val tomcatPath = ZideConfigParser.extractTomcatPath(selectedService)
        val httpPort = ZideConfigParser.extractHttpPort(properties)
        val tomcatVersion = ZideConfigParser.extractTomcatVersion(selectedService)
        val serviceKey = ZideConfigParser.extractServiceKey(selectedService)
        val repositoryModuleDir = selectedService.properties["ZIDE.REPOSITORY_MODULE_DIR"]
            ?: service.properties["ZIDE.REPOSITORY_MODULE_DIR"]
        val deployType = selectedService.properties["ZIDE.DEPLOY_TYPE"]
            ?: service.properties["ZIDE.DEPLOY_TYPE"]
            ?: "M19"
        val runtimeProperties = selectedService.properties + (properties.properties)

        var zideFolderPath = PathResolver.findDefaultZideFolder(projectPath)
        var zidePropertiesPath: String? = null
        var moduleZideProps = if (zideFolderPath != null && repositoryModuleDir != null) {
            ModuleZidePropsParser.resolveModuleZidePropsFromZideFolder(zideFolderPath, repositoryModuleDir, deployType)
        } else null

        if (zideFolderPath != null && repositoryModuleDir != null) {
            zidePropertiesPath = ModuleZidePropsParser.resolveModuleZidePropsPath(
                zideFolderPath, repositoryModuleDir, deployType
            )
        }

        if (moduleZideProps == null && repositoryModuleDir != null) {
            val pickedFolder = askUserForZideFolder(project, projectPath)
            if (pickedFolder != null) {
                zideFolderPath = pickedFolder
                zidePropertiesPath = ModuleZidePropsParser.resolveModuleZidePropsPath(
                    zideFolderPath, repositoryModuleDir, deployType
                )
                moduleZideProps = ModuleZidePropsParser.resolveModuleZidePropsFromZideFolder(
                    zideFolderPath, repositoryModuleDir, deployType
                )
                if (moduleZideProps == null) {
                    NotificationUtil.warn(
                        project,
                        "Could not resolve Zide.properties under $zideFolderPath/deployment/$repositoryModuleDir/$deployType. Continuing without launch.vmarguments."
                    )
                }
            }
        }

        if (tomcatPath == null) {
            NotificationUtil.error(project, "ZIDE.DEPLOYMENT_FOLDER not found in service.xml configuration.")
            return null
        }

        val catalinaScript = Path.of(tomcatPath, "bin",
            if (com.zoho.dzide.util.ShellUtil.isWindows) "catalina.bat" else "catalina.sh")
        if (!catalinaScript.exists()) {
            NotificationUtil.error(project, "Invalid Tomcat path: $tomcatPath\n\nEnsure it contains bin/catalina.sh")
            return null
        }

        val zidePropertiesData = ModuleZidePropsParser.readModuleZidePropsDataFromFile(zidePropertiesPath)
        val zideBuildBaseDir = Path.of(projectPath, ".zide_resources", "zide_build").toString()
        val zideBuildXmlPath = Path.of(zideBuildBaseDir, "build.xml").toString()

        val serverName = "ZIDE-$serviceKey${if (!tomcatVersion.isNullOrBlank()) " ($tomcatVersion)" else ""}"
        return TomcatServer(
            id = System.currentTimeMillis().toString(),
            name = serverName,
            path = tomcatPath,
            status = "stopped",
            port = httpPort,
            description = "Auto-configured from ZIDE service: $serviceKey",
            zideServiceKey = serviceKey,
            zideFolderPath = moduleZideProps?.zideFolderPath ?: zideFolderPath,
            zidePropertiesPath = moduleZideProps?.zidePropertiesPath ?: zidePropertiesPath,
            zideLaunchVmArguments = moduleZideProps?.launchVmArguments ?: zidePropertiesData.launchVmArguments,
            zideHookTasksRaw = zidePropertiesData.hookTasksRaw,
            zideAutoResourceCopyRaw = zidePropertiesData.autoResourceCopyRaw,
            repositoryModuleDir = repositoryModuleDir,
            deployType = deployType,
            zideRuntimeProperties = runtimeProperties,
            zideBuildXmlPath = zideBuildXmlPath,
            zideBuildBaseDir = zideBuildBaseDir
        )
    }

    private fun showServiceSelector(project: Project, serviceOptions: List<String>): String? {
        if (serviceOptions.size == 1) return serviceOptions[0]

        var selected: String? = null
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(serviceOptions)
            .setTitle("Select ZIDE Service")
            .setItemChosenCallback { selected = it }
            .createPopup()
        popup.showCenteredInCurrentWindow(project)
        return selected
    }

    @Suppress("UNUSED_PARAMETER")
    private fun askUserForZideFolder(project: Project, projectPath: String): String? {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select zide Folder")
            .withDescription("Select zide folder to resolve launch.vmarguments")
        val files = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
        return files.firstOrNull()?.path
    }
}
