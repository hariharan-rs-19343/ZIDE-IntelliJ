package com.zoho.dzide.zide

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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
        FieldDef("ZIDE.SCHEMA_NAME", "Schema Name"),
        FieldDef("ZIDE.IAM_SERVER", "IAM Server"),
        FieldDef("ZIDE.HTTP_PORT", "Http Port"),
        FieldDef("ZIDE.HTTPS_PORT", "Https Port"),
        FieldDef("ZIDE.IAM_SERVICENAME", "IAM Service Name")
    )

    private val textFields = mutableMapOf<String, JBTextField>()

    init {
        title = if (readOnly) "$serviceKey (Read Only)" else serviceKey
        setOKButtonText(if (readOnly) "Close" else "Save")
        if (readOnly) setCancelButtonText("Close")
        init()
        if (readOnly) cancelAction.isEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(6, 8, 6, 8)
        }

        for ((row, field) in fields.withIndex()) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            panel.add(JLabel(field.label), gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            val textField = JBTextField(currentProperties[field.key] ?: "")
            textField.columns = 40
            textField.isEditable = !readOnly
            textFields[field.key] = textField
            panel.add(textField, gbc)
        }

        return panel
    }

    fun getUpdatedProperties(): Map<String, String> {
        return textFields.mapValues { it.value.text.trim() }
    }
}
