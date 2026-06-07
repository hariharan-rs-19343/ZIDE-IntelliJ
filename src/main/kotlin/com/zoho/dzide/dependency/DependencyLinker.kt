package com.zoho.dzide.dependency

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

data class ResolvedDependency(
    val name: String,
    val jarFiles: List<File>,
    val scope: DependencyScope
)

class DependencyLinker(private val project: Project) {

    private val log = Logger.getInstance(DependencyLinker::class.java)

    fun linkDeploymentLibraries(deploymentPath: String, module: Module) {
        val webInfLib = File(deploymentPath, "WEB-INF/lib")
        if (!webInfLib.isDirectory) {
            log.warn("[DependencyLinker] WEB-INF/lib not found at: ${webInfLib.absolutePath}")
            return
        }

        val jars = webInfLib.listFiles { f -> f.extension.equals("jar", ignoreCase = true) }?.toList() ?: emptyList()
        if (jars.isEmpty()) {
            log.info("[DependencyLinker] No JARs found in ${webInfLib.absolutePath}")
            return
        }

        val dep = ResolvedDependency(
            name = "ZIDE-Deployment-Libs",
            jarFiles = jars,
            scope = DependencyScope.PROVIDED
        )

        link(listOf(dep), module)
        log.info("[DependencyLinker] Linked ${jars.size} JARs from ${webInfLib.absolutePath}")
    }

    fun link(dependencies: List<ResolvedDependency>, module: Module) {
        WriteAction.run<Throwable> {
            val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
            val tableModel = libraryTable.modifiableModel

            try {
                for (dep in dependencies) {
                    val library = tableModel.getLibraryByName(dep.name)
                        ?: tableModel.createLibrary(dep.name)

                    val libraryModel = library.modifiableModel
                    try {
                        cleanupInvalidRoots(libraryModel)
                        addJarsToLibrary(dep.jarFiles, libraryModel)
                    } finally {
                        libraryModel.commit()
                    }

                    addLibraryToModule(module, library, dep.scope)
                }
            } finally {
                tableModel.commit()
            }
        }
    }

    private fun addJarsToLibrary(jars: List<File>, model: Library.ModifiableModel) {
        val existingUrls = model.getUrls(OrderRootType.CLASSES).toSet()
        for (jar in jars) {
            if (!jar.exists()) continue
            val jarUrl = VfsUtil.getUrlForLibraryRoot(jar)
            if (jarUrl !in existingUrls) {
                model.addRoot(jarUrl, OrderRootType.CLASSES)
            }
        }
    }

    private fun cleanupInvalidRoots(model: Library.ModifiableModel) {
        val invalidUrls = model.getUrls(OrderRootType.CLASSES).filter { url ->
            !model.isValid(url, OrderRootType.CLASSES)
        }
        for (url in invalidUrls) {
            model.removeRoot(url, OrderRootType.CLASSES)
            log.info("[DependencyLinker] Removed invalid root: $url")
        }
    }

    private fun addLibraryToModule(module: Module, library: Library, scope: DependencyScope) {
        try {
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            val alreadyLinked = rootModel.orderEntries.any { entry ->
                entry is com.intellij.openapi.roots.LibraryOrderEntry && entry.libraryName == library.name
            }
            if (!alreadyLinked) {
                val entry = rootModel.addLibraryEntry(library)
                entry.scope = scope
            }
            rootModel.commit()
        } catch (e: Exception) {
            log.warn("[DependencyLinker] Failed to add library ${library.name} to module ${module.name}", e)
        }
    }
}
