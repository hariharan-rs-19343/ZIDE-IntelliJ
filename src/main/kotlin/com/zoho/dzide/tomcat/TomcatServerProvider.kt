package com.zoho.dzide.tomcat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.zoho.dzide.model.ProjectServerMapping
import com.zoho.dzide.model.TomcatServer
import com.zoho.dzide.persistence.TomcatServerState

@Service(Service.Level.PROJECT)
class TomcatServerProvider(private val project: Project) {

    private val state: TomcatServerState
        get() = TomcatServerState.getInstance(project)

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun fireChanged() {
        listeners.forEach { it() }
    }

    fun getServers(): List<TomcatServer> = state.getServers()

    fun getServer(serverId: String): TomcatServer? = state.getServer(serverId)

    fun addServer(server: TomcatServer) {
        state.addServer(server)
        fireChanged()
    }

    fun removeServer(serverId: String) {
        state.removeServer(serverId)
        fireChanged()
    }

    fun updateServer(serverId: String, updates: Map<String, Any?>) {
        state.updateServer(serverId) { server ->
            updates.forEach { (key, value) ->
                when (key) {
                    "name" -> server.name = value as? String ?: server.name
                    "path" -> server.path = value as? String ?: server.path
                    "status" -> server.status = value as? String ?: server.status
                    "port" -> server.port = value as? Int ?: server.port
                    "debugPort" -> server.debugPort = value as? Int
                    "description" -> server.description = value as? String
                    "deployConfiguredWarOnRun" -> server.deployConfiguredWarOnRun = value as? Boolean ?: false
                    "configuredWarFilePath" -> server.configuredWarFilePath = value as? String
                    "manualLaunchArgs" -> server.manualLaunchArgs = value as? String
                    "zideLaunchVmArguments" -> server.zideLaunchVmArguments = value as? String
                    "zideHookTasksRaw" -> server.zideHookTasksRaw = value as? String
                    "zideAutoResourceCopyRaw" -> server.zideAutoResourceCopyRaw = value as? String
                    "antHomeResolvedPath" -> server.antHomeResolvedPath = value as? String
                }
            }
        }
        fireChanged()
    }

    fun getMappings(): List<ProjectServerMapping> = state.getMappings()

    fun getProjectMapping(projectPath: String): ProjectServerMapping? {
        val basePath = project.basePath ?: return state.getProjectMapping(projectPath)
        val normalized = java.nio.file.Paths.get(projectPath).toAbsolutePath().normalize().toString()
        return state.getMappings().find { mapping ->
            val resolvedPath = mapping.projectPath
                .replace("\$PROJECT_DIR\$", basePath)
                .replace("${'$'}PROJECT_DIR${'$'}", basePath)
            try {
                java.nio.file.Paths.get(resolvedPath).toAbsolutePath().normalize().toString() == normalized
            } catch (_: Exception) {
                false
            }
        }
    }

    fun setProjectMapping(mapping: ProjectServerMapping) {
        state.setProjectMapping(mapping)
        fireChanged()
    }

    companion object {
        fun getInstance(project: Project): TomcatServerProvider =
            project.getService(TomcatServerProvider::class.java)
    }
}
