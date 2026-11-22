package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.zoho.dzide.zide.ZideConfigRepoService
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ZohoRepoConfigurable : Configurable {

    private var usernameField: JBTextField? = null
    private var passwordField: PasswordFieldWithToggle? = null
    private var hintLabel: JBLabel? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Zoho Repository"

    override fun createComponent(): JComponent {
        usernameField = JBTextField()
        passwordField = PasswordFieldWithToggle()
        hintLabel = GlobalCredentialHint.createLabel()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Username:", usernameField!!, 1, false)
            .addLabeledComponent("Password:", passwordField!!, 1, false)
            .addComponentToRightColumn(hintLabel!!, 1)
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
        updateHintLabel()
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        usernameField?.text = settings.zohoRepoUsername
        passwordField?.text = settings.getPassword(ZideSettingsState.KEY_ZOHO_REPO_PASSWORD)
        updateHintLabel()
    }

    private fun updateHintLabel() {
        val filled = !usernameField?.text.isNullOrBlank() && !passwordField?.text.isNullOrEmpty()
        GlobalCredentialHint.update(
            hintLabel,
            fieldsFilled = filled,
            globalSource = if (filled) null else CredentialResolver.resolveZohoRepoGlobal(ZideConfigRepoService.CLONE_URL)?.source,
            lookedIn = "${CredentialResolver.SOURCE_HGRC} [auth] or ${CredentialResolver.SOURCE_NETRC}",
            mandatory = false
        )
    }

    override fun disposeUIResources() {
        usernameField = null
        passwordField = null
        hintLabel = null
        panel = null
    }
}
