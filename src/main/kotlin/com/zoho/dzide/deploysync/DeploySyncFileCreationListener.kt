package com.zoho.dzide.deploysync

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent

class DeploySyncFileCreationListener : BulkFileListener {

    private val log = Logger.getInstance(DeploySyncFileCreationListener::class.java)

    override fun before(events: List<VFileEvent>) {
        for (event in events) {
            if (event !is VFileDeleteEvent) continue
            val file = event.file
            if (file.isDirectory) continue

            // Ignore compiled class events (avoid loops). Java deletes sync .class cleanup.
            val ext = file.extension
            if (ext == "class") continue

            val filePath = file.path
            log.info("Deploy sync: delete detected — $filePath")

            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val basePath = project.basePath ?: continue
                if (belongsToProject(filePath, basePath)) {
                    ResourceSyncManager.getInstance(project).handleFileDelete(filePath)
                    break
                }
            }
        }
    }

    override fun after(events: List<VFileEvent>) {
        for (event in events) {
            if (event !is VFileCreateEvent && event !is VFileMoveEvent && event !is VFileContentChangeEvent) continue

            val filePath = event.path
            // Skip directories and compiled .class (avoid copy/hot-swap loops). Allow .java.
            if (filePath.endsWith("/") || filePath.endsWith(".class")) continue

            val file = event.file
            if (file != null && file.isDirectory) continue

            log.info("Deploy sync: ${event.javaClass.simpleName} — $filePath")

            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val basePath = project.basePath ?: continue
                if (belongsToProject(filePath, basePath)) {
                    ResourceSyncManager.getInstance(project).handleDocumentSave(filePath)
                    break
                }
            }
        }
    }

    /** Match project root with a path boundary so sibling folders are not claimed. */
    private fun belongsToProject(filePath: String, basePath: String): Boolean {
        if (filePath == basePath) return true
        val prefix = if (basePath.endsWith("/")) basePath else "$basePath/"
        return filePath.startsWith(prefix)
    }
}
