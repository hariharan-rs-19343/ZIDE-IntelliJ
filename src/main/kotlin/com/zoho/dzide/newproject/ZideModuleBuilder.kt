package com.zoho.dzide.newproject

import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.ui.Messages
import com.zoho.dzide.settings.ZideSettingsState
import javax.swing.Icon

class ZideModuleBuilder : ModuleBuilder() {

    override fun getModuleType(): ModuleType<*> = StdModuleTypes.JAVA

    override fun getBuilderId(): String = "com.zoho.dzide.newproject"

    override fun getPresentableName(): String = "ZIDE"

    override fun getDescription(): String = "Create a new ZIDE project with Tomcat server management and deploy-sync integration."

    override fun getNodeIcon(): Icon? = null

    override fun getGroupName(): String = "ZIDE"

    override fun getParentGroup(): String = "New Project"

    override fun getWeight(): Int = 0

    override fun setupRootModel(modifiableRootModel: ModifiableRootModel) {
        doAddContentEntry(modifiableRootModel)
    }

    override fun getCustomOptionsStep(context: WizardContext?, parentDisposable: com.intellij.openapi.Disposable?): ModuleWizardStep? {
        if (!ensureCmToolToken()) return null
        return super.getCustomOptionsStep(context, parentDisposable)
    }

    private fun ensureCmToolToken(): Boolean {
        val settings = ZideSettingsState.getInstance()
        if (settings.cmToolAuthToken.isNotBlank()) return true

        val token = Messages.showInputDialog(
            "CMTool Auth Token is required to create a ZIDE project.\n\nEnter your CMTool Auth Token:",
            "CMTool Auth Token Required",
            null
        )

        if (token.isNullOrBlank()) {
            Messages.showWarningDialog(
                "Cannot create ZIDE project without CMTool Auth Token.\nConfigure it in Settings > Tools > Zide > CMTool.",
                "CMTool Auth Token Required"
            )
            return false
        }

        settings.cmToolAuthToken = token.trim()
        return true
    }
}
