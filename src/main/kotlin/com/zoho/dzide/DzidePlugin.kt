package com.zoho.dzide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zoho.dzide.util.NotificationUtil
import java.io.File

class DzidePlugin : ProjectActivity {

    private val log = Logger.getInstance(DzidePlugin::class.java)

    override suspend fun execute(project: Project) {
        log.info("DZIDE plugin initialized for project: ${project.name}")

        val wgetrcFile = File(System.getProperty("user.home"), ".wgetrc")
        if (!wgetrcFile.exists()) {
            NotificationUtil.warn(
                project,
                "~/.wgetrc file is missing. Configure Wget credentials in Settings > Tools > Zide > Wget Configuration."
            )
        }
    }
}
