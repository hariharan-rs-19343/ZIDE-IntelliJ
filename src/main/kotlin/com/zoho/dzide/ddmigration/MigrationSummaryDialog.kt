package com.zoho.dzide.ddmigration

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.zoho.dzide.util.NotificationUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class MigrationSummaryDialog(
    private val project: Project,
    private val summary: MigrationSummary
) : DialogWrapper(project) {

    private val log = Logger.getInstance(MigrationSummaryDialog::class.java)
    private val logArea = JTextArea().apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    init {
        title = "DD Migration Summary"
        setOKButtonText("Execute Migration")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabbedPane = JTabbedPane()

        val sqlArea = JTextArea(summary.sqlContent).apply {
            isEditable = false
            font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        }
        tabbedPane.addTab("SQL Preview", JScrollPane(sqlArea))

        val summaryPanel = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            val info = StringBuilder()
            info.appendLine("File: ${summary.fileName}")
            info.appendLine("Statements: ${summary.statementCount}")
            info.appendLine("Operations: ${summary.operationTypes.joinToString(", ")}")
            info.appendLine()
            info.appendLine("Database: ${summary.dbConnection.database}")
            info.appendLine("Host: ${summary.dbConnection.host}:${summary.dbConnection.port}")
            info.appendLine("Schema: ${summary.dbConnection.schemaName}")
            info.appendLine("Type: ${if (summary.dbConnection.isMySQL) "MySQL" else "PostgreSQL"}")

            add(JTextArea(info.toString()).apply {
                isEditable = false
                font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
            }, BorderLayout.CENTER)
        }
        tabbedPane.addTab("Summary", summaryPanel)

        tabbedPane.addTab("Execution Log", JScrollPane(logArea))

        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(700, 500)
        panel.add(tabbedPane, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        val confirm = Messages.showYesNoDialog(
            project,
            "Execute ${summary.statementCount} SQL statements against ${summary.dbConnection.database}?\n\nThis cannot be undone.",
            "Confirm Migration",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Executing DD Migration", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val executor = MigrationSqlExecutor()
                val result = executor.execute(summary) { msg ->
                    indicator.text = msg
                    SwingUtilities.invokeLater {
                        logArea.append("$msg\n")
                        logArea.caretPosition = logArea.document.length
                    }
                }

                SwingUtilities.invokeLater {
                    if (result.success) {
                        logArea.append("\n=== Migration completed successfully ===\n")
                        NotificationUtil.info(project, "DD Migration completed: ${result.totalSchemas} schemas processed")
                    } else {
                        logArea.append("\n=== Migration completed with errors ===\n")
                        for (sr in result.schemaResults.filter { !it.success }) {
                            logArea.append("  Schema ${sr.schemaName}: ${sr.failed} failures\n")
                            for (err in sr.errors) {
                                logArea.append("    - $err\n")
                            }
                        }
                        NotificationUtil.warn(project, "DD Migration completed with errors. Check the execution log.")
                    }
                }
            }
        })
    }
}
