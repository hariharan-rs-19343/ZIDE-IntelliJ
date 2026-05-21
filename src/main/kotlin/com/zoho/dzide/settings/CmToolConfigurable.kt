package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CmToolConfigurable : Configurable {

    private var authTokenField: PasswordFieldWithToggle? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "CMTool"

    override fun createComponent(): JComponent {
        authTokenField = PasswordFieldWithToggle()

        val helpText = "<html><small>Refer the link below to configure the CMTool Auth Token:<br>" +
                "<a href=\"https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/cmtools-auth-token-required\">" +
                "https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/cmtools-auth-token-required</a></small></html>"
        val helpLabel = JBLabel(helpText)
        helpLabel.setCopyable(true)

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Auth Token:", authTokenField!!, 1, false)
            .addComponentToRightColumn(helpLabel, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        return authTokenField?.text != settings.cmToolAuthToken
    }

    override fun apply() {
        val settings = ZideSettingsState.getInstance()
        settings.cmToolAuthToken = authTokenField?.text ?: ""
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        authTokenField?.text = settings.cmToolAuthToken
    }

    override fun disposeUIResources() {
        authTokenField = null
        panel = null
    }
}
