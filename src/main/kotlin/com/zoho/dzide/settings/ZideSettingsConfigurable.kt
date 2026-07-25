package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ZideSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var replacerEveryStartCheck: JBCheckBox? = null

    override fun getDisplayName(): String = "Zide"

    override fun createComponent(): JComponent {
        replacerEveryStartCheck = JBCheckBox(
            "Run config replace on every server start (Eclipse need_replacer_everystart)",
            true
        )
        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Configure ZIDE tools and repository settings from the sub-pages below."))
            .addComponent(replacerEveryStartCheck!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        return replacerEveryStartCheck?.isSelected != settings.replacerEveryStart
    }

    override fun apply() {
        ZideSettingsState.getInstance().replacerEveryStart =
            replacerEveryStartCheck?.isSelected ?: true
    }

    override fun reset() {
        replacerEveryStartCheck?.isSelected =
            ZideSettingsState.getInstance().replacerEveryStart
    }

    override fun disposeUIResources() {
        panel = null
        replacerEveryStartCheck = null
    }
}
