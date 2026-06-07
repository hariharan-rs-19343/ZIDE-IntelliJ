package com.zoho.dzide.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.zoho.dzide.db.DatabaseReinitializer
import com.zoho.dzide.db.MySQLReinitializer
import com.zoho.dzide.db.PostgresReinitializer
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.zide.ZideConfigParser

class ReinitDbAction : AnAction("Reinit DB", "Drop and recreate the local database", null) {

    private val log = Logger.getInstance(ReinitDbAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return

        val zideConfig = ZideConfigParser.readZideConfig(projectPath)
        val props = zideConfig?.properties?.properties ?: run {
            NotificationUtil.error(project, "Cannot read ZIDE properties. Check .zide_resources/zide_properties.xml")
            return
        }

        val dbType = props["ZIDE_DB_TYPE"] ?: props["ZIDE.DB_TYPE"] ?: "PGSQL"
        val dbHost = props["ZIDE_DB_HOST"] ?: props["ZIDE.DB_HOST"] ?: "localhost"
        val dbPort = when {
            dbType.equals("MYSQL", ignoreCase = true) -> (props["ZIDE_DB_PORT"] ?: "3306").toIntOrNull() ?: 3306
            else -> (props["ZIDE_DB_PORT"] ?: "5432").toIntOrNull() ?: 5432
        }
        val dbName = props["ZIDE_DB_NAME"] ?: props["ZIDE.DB_NAME"] ?: "sasdb"
        val dbUser = props["ZIDE_DB_USER"] ?: props["ZIDE.DB_USER"] ?: "root"
        val dbPass = props["ZIDE_DB_PASS"] ?: props["ZIDE.DB_PASS"] ?: ""
        val schemaName = props["ZIDE.SCHEMA_NAME"] ?: "jbossdb"

        val dbLabel = if (dbType.equals("MYSQL", ignoreCase = true)) "MySQL" else "PostgreSQL"
        val confirm = Messages.showYesNoDialog(
            project,
            "This will DROP and RECREATE the database '$dbName' on $dbHost:$dbPort ($dbLabel).\n\nAll data will be lost. Continue?",
            "Reinit Database",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reinitializing $dbLabel Database", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Reinitializing $dbLabel database '$dbName'..."

                try {
                    val reinitializer: DatabaseReinitializer = if (dbType.equals("MYSQL", ignoreCase = true)) {
                        MySQLReinitializer()
                    } else {
                        PostgresReinitializer()
                    }

                    reinitializer.reinit(dbHost, dbPort, dbName, dbUser, dbPass, schemaName)
                    NotificationUtil.info(project, "$dbLabel database '$dbName' reinitialized successfully.")
                    log.info("[ReinitDB] $dbLabel reinit completed for $dbName on $dbHost:$dbPort")
                } catch (ex: Exception) {
                    log.error("[ReinitDB] Failed to reinit $dbLabel: ${ex.message}", ex)
                    NotificationUtil.error(project, "Database reinit failed: ${ex.message}")
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }
}
