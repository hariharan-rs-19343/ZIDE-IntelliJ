package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ZideSettingsConfigurable : Configurable {

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Zide"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Configure ZIDE tools and repository settings from the sub-pages below."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun disposeUIResources() {
        panel = null
    }
}
