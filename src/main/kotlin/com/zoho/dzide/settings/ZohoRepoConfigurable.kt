package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ZohoRepoConfigurable : Configurable {

    private var usernameField: JBTextField? = null
    private var passwordField: PasswordFieldWithToggle? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Zoho Repository"

    override fun createComponent(): JComponent {
        usernameField = JBTextField()
        passwordField = PasswordFieldWithToggle()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Username:", usernameField!!, 1, false)
            .addLabeledComponent("Password:", passwordField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        if (usernameField?.text?.trim() != settings.zohoRepoUsername) return true
        val storedPassword = settings.getPassword(ZideSettingsState.KEY_ZOHO_REPO_PASSWORD)
        if (passwordField?.text != storedPassword) return true
        return false
    }

    override fun apply() {
        val settings = ZideSettingsState.getInstance()
        settings.zohoRepoUsername = usernameField?.text?.trim() ?: ""
        settings.setPassword(ZideSettingsState.KEY_ZOHO_REPO_PASSWORD, passwordField?.text ?: "")
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        usernameField?.text = settings.zohoRepoUsername
        passwordField?.text = settings.getPassword(ZideSettingsState.KEY_ZOHO_REPO_PASSWORD)
    }

    override fun disposeUIResources() {
        usernameField = null
        passwordField = null
        panel = null
    }
}
