package com.zoho.dzide.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.zoho.dzide.util.ProtocConfig
import com.zoho.dzide.util.ZeroProxyManager
import javax.swing.JComponent
import javax.swing.JPanel

class ZideSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var replacerEveryStartCheck: JBCheckBox? = null
    private var useZeroTrustProxyCheck: JBCheckBox? = null
    private var protocEnabledCheck: JBCheckBox? = null
    private var protocPathField: TextFieldWithBrowseButton? = null
    private var protocPathRow: JPanel? = null

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
        protocEnabledCheck = JBCheckBox("Use custom protoc for ANT builds", false)
        protocPathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                null,
                FileChooserDescriptor(true, true, false, false, false, false)
                    .withTitle("Select protoc")
                    .withDescription("Choose the protoc executable or its bin folder")
            )
        }
        val protocHint = JBLabel("protoc executable or its bin folder (passed as protobuf_local_dir / protobuf_dir and added to PATH)")
            .apply { componentStyle = UIUtil.ComponentStyle.SMALL; foreground = UIUtil.getContextHelpForeground() }
        protocPathRow = FormBuilder.createFormBuilder()
            .addLabeledComponent("Protoc path:", protocPathField!!, 1, false)
            .addComponentToRightColumn(protocHint, 1)
            .panel
        protocEnabledCheck!!.addItemListener { updateProtocRowVisibility() }

        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Configure ZIDE tools and repository settings from the sub-pages below."))
            .addComponent(replacerEveryStartCheck!!, 1)
            .addComponent(useZeroTrustProxyCheck!!, 1)
            .addComponent(protocEnabledCheck!!, 1)
            .addComponent(protocPathRow!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        updateProtocRowVisibility()
        return panel!!
    }

    private fun updateProtocRowVisibility() {
        protocPathRow?.isVisible = protocEnabledCheck?.isSelected == true
        panel?.revalidate()
        panel?.repaint()
    }

    override fun isModified(): Boolean {
        val settings = ZideSettingsState.getInstance()
        return replacerEveryStartCheck?.isSelected != settings.replacerEveryStart ||
            useZeroTrustProxyCheck?.isSelected != settings.useZeroTrustProxy ||
            protocEnabledCheck?.isSelected != settings.protocEnabled ||
            protocPathField?.text?.trim() != settings.protocPath
    }

    override fun apply() {
        val protocEnabled = protocEnabledCheck?.isSelected ?: false
        val protocPath = protocPathField?.text?.trim().orEmpty()
        if (protocEnabled && ProtocConfig.binDir(protocPath) == null) {
            throw ConfigurationException(
                "Protoc path is required when 'Use custom protoc' is enabled (protoc executable or its bin folder).",
                "Protoc Path Required"
            )
        }

        val settings = ZideSettingsState.getInstance()
        settings.replacerEveryStart = replacerEveryStartCheck?.isSelected ?: true
        settings.protocEnabled = protocEnabled
        settings.protocPath = protocPath
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
        protocEnabledCheck?.isSelected = settings.protocEnabled
        protocPathField?.text = settings.protocPath
        updateProtocRowVisibility()
    }

    override fun disposeUIResources() {
        panel = null
        replacerEveryStartCheck = null
        useZeroTrustProxyCheck = null
        protocEnabledCheck = null
        protocPathField = null
        protocPathRow = null
    }
}
