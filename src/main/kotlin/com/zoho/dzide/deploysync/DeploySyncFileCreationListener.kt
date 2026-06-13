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

            val ext = file.extension
            if (ext == "java" || ext == "class") continue

            val filePath = file.path
            log.info("Deploy sync: delete detected — $filePath")

            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val basePath = project.basePath ?: continue
                if (filePath.startsWith(basePath)) {
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
            if (filePath.endsWith("/") || filePath.endsWith(".java") || filePath.endsWith(".class")) continue

            val file = event.file
            if (file != null && file.isDirectory) continue

            log.info("Deploy sync: ${event.javaClass.simpleName} — $filePath")

            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val basePath = project.basePath ?: continue
                if (filePath.startsWith(basePath)) {
                    ResourceSyncManager.getInstance(project).handleDocumentSave(filePath)
                    break
                }
            }
        }
    }
}
