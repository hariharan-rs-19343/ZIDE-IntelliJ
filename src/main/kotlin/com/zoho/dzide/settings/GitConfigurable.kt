package com.zoho.dzide.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class GitConfigurable : Configurable {

    private var gitPathField: TextFieldWithBrowseButton? = null
    private var usernameField: JBTextField? = null
    private var passwordField: PasswordFieldWithToggle? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Git"

    override fun createComponent(): JComponent {
        gitPathField = TextFieldWithBrowseButton()
        gitPathField!!.addBrowseFolderListener(
            "Select Git Executable Path",
            "Choose the directory containing the git executable",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        val autoDetectButton = JButton("Auto Detect")
        autoDetectButton.addActionListener { autoDetectGitPath() }

        val pathPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        pathPanel.add(gitPathField)
        pathPanel.add(autoDetectButton)

        usernameField = JBTextField()
        passwordField = PasswordFieldWithToggle()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Git Path:", pathPanel, 1, false)
            .addLabeledComponent("Username:", usernameField!!, 1, false)
            .addLabeledComponent("Password:", passwordField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    private fun autoDetectGitPath() {
        val candidates = listOf("/usr/bin/git", "/usr/local/bin/git")
        for (candidate in candidates) {
            if (File(candidate).exists()) {
                gitPathField?.text = File(candidate).parent
                return
            }
        }
        try {
            val process = ProcessBuilder("which", "git").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.isNotEmpty() && File(result).exists()) {
                gitPathField?.text = File(result).parent
                return
            }
        } catch (_: Exception) {}

        gitPathField?.text = ""
        javax.swing.JOptionPane.showMessageDialog(panel, "Git executable not found.", "Auto Detect", javax.swing.JOptionPane.WARNING_MESSAGE)
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        if (gitPathField?.text?.trim() != settings.gitPath) return true
        if (usernameField?.text?.trim() != settings.gitUsername) return true
        val storedPassword = settings.getPassword(ZideSettingsState.KEY_GIT_PASSWORD)
        if (passwordField?.text != storedPassword) return true
        return false
    }

    override fun apply() {
        val settings = ZideSettingsState.getInstance()
        settings.gitPath = gitPathField?.text?.trim() ?: ""
        settings.gitUsername = usernameField?.text?.trim() ?: ""
        settings.setPassword(ZideSettingsState.KEY_GIT_PASSWORD, passwordField?.text ?: "")
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        gitPathField?.text = settings.gitPath
        usernameField?.text = settings.gitUsername
        passwordField?.text = settings.getPassword(ZideSettingsState.KEY_GIT_PASSWORD)
    }

    override fun disposeUIResources() {
        gitPathField = null
        usernameField = null
        passwordField = null
        panel = null
    }
}
