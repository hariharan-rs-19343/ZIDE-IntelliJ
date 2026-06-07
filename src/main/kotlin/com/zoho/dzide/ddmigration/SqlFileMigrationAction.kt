package com.zoho.dzide.ddmigration

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.zoho.dzide.util.NotificationUtil
import com.zoho.dzide.zide.ZideConfigParser
import javax.swing.SwingUtilities

class SqlFileMigrationAction : AnAction("Run SQL Migration", "Upload and execute a dd-changes.sql file", null) {

    private val log = Logger.getInstance(SqlFileMigrationAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectPath = project.basePath ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("sql")
        descriptor.title = "Select DD Migration SQL File"
        val vFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val dbConnection = readDbConnection(projectPath)
        if (dbConnection == null) {
            NotificationUtil.error(project, "Cannot read database configuration from ZIDE properties.")
            return
        }

        val summary = MigrationSummary.fromSqlFile(vFile.path, dbConnection)

        if (summary.sqlQueries.isEmpty()) {
            NotificationUtil.warn(project, "No executable SQL statements found in ${vFile.name}")
            return
        }

        SwingUtilities.invokeLater {
            MigrationSummaryDialog(project, summary).show()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    companion object {
        fun readDbConnection(projectPath: String): DBConnectionDetails? {
            val zideConfig = ZideConfigParser.readZideConfig(projectPath)
            val props = zideConfig?.properties?.properties ?: return null

            val dbType = props["ZIDE_DB_TYPE"] ?: props["ZIDE.DB_TYPE"] ?: "PGSQL"
            val isMySQL = dbType.equals("MYSQL", ignoreCase = true)

            return DBConnectionDetails(
                host = props["ZIDE_DB_HOST"] ?: props["ZIDE.DB_HOST"] ?: "localhost",
                port = (props["ZIDE_DB_PORT"] ?: if (isMySQL) "3306" else "5432").toIntOrNull() ?: if (isMySQL) 3306 else 5432,
                database = props["ZIDE_DB_NAME"] ?: props["ZIDE.DB_NAME"] ?: "sasdb",
                username = props["ZIDE_DB_USER"] ?: props["ZIDE.DB_USER"] ?: "root",
                password = props["ZIDE_DB_PASS"] ?: props["ZIDE.DB_PASS"] ?: "",
                schemaName = props["ZIDE.SCHEMA_NAME"] ?: "jbossdb",
                isMySQL = isMySQL
            )
        }
    }
}
