package com.zoho.dzide.newproject

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

class ZideNewProjectWizard : GeneratorNewProjectWizard {

    override val id: String = "zide-project"

    override val name: String = "ZIDE Project"

    override val icon: Icon = IconLoader.getIcon("/icons/zide-toolwindow.svg", ZideNewProjectWizard::class.java)

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        val root = RootNewProjectWizardStep(context)
        return NewProjectWizardChainStep(NewProjectWizardBaseStep(root))
            .nextStep(::ZideNewProjectWizardStep)
    }
}
