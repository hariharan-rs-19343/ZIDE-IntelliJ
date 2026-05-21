package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

class WgetConfigurable : Configurable {

    private var usernameField: JBTextField? = null
    private var passwordField: PasswordFieldWithToggle? = null
    private var errorLabel: JBLabel? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Wget Configuration"

    override fun createComponent(): JComponent {
        usernameField = JBTextField()
        passwordField = PasswordFieldWithToggle()

        errorLabel = JBLabel()
        errorLabel!!.foreground = JBColor.RED
        updateErrorLabel()

        val helpText = "<html><small>Refer the link below to get the credential:<br>" +
                "<a href=\"https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/wgetrc-file-is-missing\">" +
                "https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/wgetrc-file-is-missing</a></small></html>"
        val helpLabel = JBLabel(helpText)
        helpLabel.setCopyable(true)

        panel = FormBuilder.createFormBuilder()
            .addComponent(errorLabel!!, 0)
            .addLabeledComponent("Username:", usernameField!!, 1, false)
            .addLabeledComponent("Password:", passwordField!!, 1, false)
            .addComponentToRightColumn(helpLabel, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    private fun updateErrorLabel() {
        val wgetrcFile = File(System.getProperty("user.home"), ".wgetrc")
        if (!wgetrcFile.exists()) {
            errorLabel?.text = "~/.wgetrc not found. Credentials will be written when you click Apply."
            errorLabel?.isVisible = true
        } else {
            errorLabel?.text = ""
            errorLabel?.isVisible = false
        }
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        if (usernameField?.text?.trim() != settings.wgetUsername) return true
        val storedPassword = settings.getPassword(ZideSettingsState.KEY_WGET_PASSWORD)
        if (passwordField?.text != storedPassword) return true
        return false
    }

    override fun apply() {
        val settings = ZideSettingsState.getInstance()
        val username = usernameField?.text?.trim() ?: ""
        val password = passwordField?.text ?: ""

        settings.wgetUsername = username
        settings.setPassword(ZideSettingsState.KEY_WGET_PASSWORD, password)

        if (username.isNotEmpty() && password.isNotEmpty()) {
            writeWgetrc(username, password)
        }
        updateErrorLabel()
    }

    private fun writeWgetrc(username: String, password: String) {
        val wgetrcFile = File(System.getProperty("user.home"), ".wgetrc")
        if (wgetrcFile.exists()) {
            var content = wgetrcFile.readText()
            val userRegex = Regex("""^user\s*=.*$""", RegexOption.MULTILINE)
            val passRegex = Regex("""^password\s*=.*$""", RegexOption.MULTILINE)

            content = if (userRegex.containsMatchIn(content)) {
                userRegex.replace(content, "user=$username")
            } else {
                "$content\nuser=$username"
            }
            content = if (passRegex.containsMatchIn(content)) {
                passRegex.replace(content, "password=$password")
            } else {
                "$content\npassword=$password"
            }
            wgetrcFile.writeText(content.trimEnd() + "\n")
        } else {
            wgetrcFile.writeText("user=$username\npassword=$password\n")
        }
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        usernameField?.text = settings.wgetUsername
        passwordField?.text = settings.getPassword(ZideSettingsState.KEY_WGET_PASSWORD)
        updateErrorLabel()
    }

    override fun disposeUIResources() {
        usernameField = null
        passwordField = null
        errorLabel = null
        panel = null
    }
}
