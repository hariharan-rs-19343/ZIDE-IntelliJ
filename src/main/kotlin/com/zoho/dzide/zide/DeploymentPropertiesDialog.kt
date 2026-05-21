package com.zoho.dzide.zide

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.zoho.dzide.settings.PasswordFieldWithToggle
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class DeploymentPropertiesDialog(
    project: Project,
    private val serviceKey: String,
    private val currentProperties: Map<String, String>,
    private val readOnly: Boolean = false
) : DialogWrapper(project, true) {

    private data class FieldDef(val key: String, val label: String)

    private val fields = listOf(
        FieldDef("ZIDE.HOST_NAME", "Host Name"),
        FieldDef("ZIDE.USER_MAIL", "User EMail"),
        FieldDef("ZIDE.IAM_SERVER", "IAM Server"),
        FieldDef("ZIDE.HTTP_PORT", "Http Port"),
        FieldDef("ZIDE.HTTPS_PORT", "Https Port"),
        FieldDef("ZIDE.IAM_SERVICENAME", "IAM Service Name")
    )

    private val textFields = mutableMapOf<String, JBTextField>()

    private val mysqlRadio = JRadioButton("MySQL")
    private val postgresRadio = JRadioButton("PostgreSQL")
    private var dbConfigPanel: JPanel? = null
    private var dbHostField: JBTextField? = null
    private var dbUserField: JBTextField? = null
    private var dbPassField: PasswordFieldWithToggle? = null
    private var dbNameField: JBTextField? = null
    private var dbSchemaField: JBTextField? = null
    private var dbNameLabel: JLabel? = null
    private var dbConfigVisible = false

    init {
        title = if (readOnly) "$serviceKey (Read Only)" else serviceKey
        setOKButtonText(if (readOnly) "Close" else "Save")
        if (readOnly) setCancelButtonText("Close")
        init()
        if (readOnly) cancelAction.isEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(6, 8, 6, 8)
        }

        var row = 0
        for (field in fields) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            formPanel.add(JLabel(field.label), gbc)

            gbc.gridx = 1; gbc.weightx = 1.0
            val textField = JBTextField(currentProperties[field.key] ?: "")
            textField.columns = 40
            textField.isEditable = !readOnly
            textFields[field.key] = textField
            formPanel.add(textField, gbc)
            row++
        }

        // Separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        row++

        // DB Type radio buttons
        val dbGroup = ButtonGroup()
        dbGroup.add(mysqlRadio)
        dbGroup.add(postgresRadio)

        val currentVendor = currentProperties["ZIDE_DB_TYPE"] ?: "mysql"
        if (currentVendor.uppercase() == "PGSQL" || currentVendor.lowercase().contains("postgres")) {
            postgresRadio.isSelected = true
        } else {
            mysqlRadio.isSelected = true
        }
        mysqlRadio.isEnabled = !readOnly
        postgresRadio.isEnabled = !readOnly

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        formPanel.add(JLabel("Database Type"), gbc)
        val radioPanel = JPanel().apply {
            add(mysqlRadio)
            add(postgresRadio)
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(radioPanel, gbc)
        row++

        // Database Configuration toggle button
        val toggleButton = JButton("Database Configuration")
        toggleButton.addActionListener {
            dbConfigVisible = !dbConfigVisible
            dbConfigPanel?.isVisible = dbConfigVisible
            pack()
        }
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(toggleButton, gbc)
        gbc.gridwidth = 1
        row++

        // Database Configuration expandable panel
        dbConfigPanel = createDbConfigPanel()
        dbConfigPanel!!.isVisible = false
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(dbConfigPanel!!, gbc)
        gbc.gridwidth = 1
        row++

        // Listen to radio changes for PostgreSQL-specific fields
        mysqlRadio.addActionListener { updatePostgresFieldsVisibility() }
        postgresRadio.addActionListener { updatePostgresFieldsVisibility() }
        updatePostgresFieldsVisibility()

        mainPanel.add(formPanel, BorderLayout.NORTH)
        return mainPanel
    }

    private fun createDbConfigPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Database Configuration")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        var row = 0

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Hostname"), gbc)
        dbHostField = JBTextField(currentProperties["ZIDE_DB_HOST"] ?: "localhost")
        dbHostField!!.columns = 30
        dbHostField!!.isEditable = !readOnly
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dbHostField!!, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Username"), gbc)
        dbUserField = JBTextField(currentProperties["ZIDE_DB_USER"] ?: "root")
        dbUserField!!.columns = 30
        dbUserField!!.isEditable = !readOnly
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dbUserField!!, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Password"), gbc)
        dbPassField = PasswordFieldWithToggle()
        dbPassField!!.text = currentProperties["ZIDE_DB_PASS"] ?: ""
        dbPassField!!.passwordField.isEditable = !readOnly
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dbPassField!!, gbc)
        row++

        // PostgreSQL-specific fields
        dbNameLabel = JLabel("Database Name")
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(dbNameLabel!!, gbc)
        dbNameField = JBTextField(currentProperties["ZIDE_DB_NAME"] ?: "")
        dbNameField!!.columns = 30
        dbNameField!!.isEditable = !readOnly
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dbNameField!!, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        val schemaLabel = JLabel("Schema Name")
        panel.add(schemaLabel, gbc)
        dbSchemaField = JBTextField(currentProperties["ZIDE.SCHEMA_NAME"] ?: "")
        dbSchemaField!!.columns = 30
        dbSchemaField!!.isEditable = !readOnly
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dbSchemaField!!, gbc)

        return panel
    }

    private fun updatePostgresFieldsVisibility() {
        val isPostgres = postgresRadio.isSelected
        dbNameLabel?.isVisible = isPostgres
        dbNameField?.isVisible = isPostgres
    }

    fun getUpdatedProperties(): Map<String, String> {
        val result = textFields.mapValues { it.value.text.trim() }.toMutableMap()
        result["ZIDE_DB_TYPE"] = if (postgresRadio.isSelected) "PGSQL" else "MYSQL"
        result["ZIDE_DB_HOST"] = dbHostField?.text?.trim() ?: ""
        result["ZIDE_DB_USER"] = dbUserField?.text?.trim() ?: ""
        result["ZIDE_DB_PASS"] = dbPassField?.text ?: ""
        if (postgresRadio.isSelected) {
            result["ZIDE_DB_NAME"] = dbNameField?.text?.trim() ?: ""
        }
        result["ZIDE.SCHEMA_NAME"] = dbSchemaField?.text?.trim() ?: ""
        return result
    }
}
