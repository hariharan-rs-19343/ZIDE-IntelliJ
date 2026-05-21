package com.zoho.dzide.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBPasswordField
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class PasswordFieldWithToggle : JPanel(BorderLayout()) {

    val passwordField = JBPasswordField()
    private val toggleButton = JButton(AllIcons.Actions.Show)
    private var visible = false
    private val defaultEchoChar = passwordField.echoChar

    init {
        toggleButton.isFocusable = false
        toggleButton.toolTipText = "Show/Hide"
        toggleButton.addActionListener { toggle() }

        add(passwordField, BorderLayout.CENTER)
        add(toggleButton, BorderLayout.EAST)
    }

    private fun toggle() {
        visible = !visible
        if (visible) {
            passwordField.echoChar = 0.toChar()
            toggleButton.icon = AllIcons.Actions.Preview
        } else {
            passwordField.echoChar = defaultEchoChar
            toggleButton.icon = AllIcons.Actions.Show
        }
    }

    var text: String
        get() = String(passwordField.password)
        set(value) { passwordField.text = value }

    val password: CharArray
        get() = passwordField.password
}
