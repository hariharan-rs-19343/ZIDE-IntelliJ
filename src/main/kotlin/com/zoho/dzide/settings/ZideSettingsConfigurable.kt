package com.zoho.dzide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.zoho.dzide.util.ZeroProxyManager
import javax.swing.JComponent
import javax.swing.JPanel

class ZideSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var replacerEveryStartCheck: JBCheckBox? = null
    private var useZeroTrustProxyCheck: JBCheckBox? = null

    override fun getDisplayName(): String = "Zide"

    override fun createComponent(): JComponent {
        replacerEveryStartCheck = JBCheckBox(
            "Run config replace on every server start (Eclipse need_replacer_everystart)",
            true
        )
        useZeroTrustProxyCheck = JBCheckBox(
            "Use ZeroTrust proxy (when ZERO_HELPER_STATUS=ON, route IDE, git, wget, Ant, and Tomcat via the Zero Agent)",
            true
        )
        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Configure ZIDE tools and repository settings from the sub-pages below."))
            .addComponent(replacerEveryStartCheck!!, 1)
            .addComponent(useZeroTrustProxyCheck!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        return replacerEveryStartCheck?.isSelected != settings.replacerEveryStart ||
            useZeroTrustProxyCheck?.isSelected != settings.useZeroTrustProxy
    }

    override fun apply() {
        val settings = ZideSettingsState.getInstance()
        settings.replacerEveryStart = replacerEveryStartCheck?.isSelected ?: true
        val useZeroTrustProxy = useZeroTrustProxyCheck?.isSelected ?: true
        if (useZeroTrustProxy != settings.useZeroTrustProxy) {
            settings.useZeroTrustProxy = useZeroTrustProxy
            ZeroProxyManager.syncIdeProxy()
        }
    }

    override fun reset() {
        val settings = ZideSettingsState.getInstance()
        replacerEveryStartCheck?.isSelected = settings.replacerEveryStart
        useZeroTrustProxyCheck?.isSelected = settings.useZeroTrustProxy
    }

    override fun disposeUIResources() {
        panel = null
        replacerEveryStartCheck = null
        useZeroTrustProxyCheck = null
    }
}
