package com.zoho.dzide.newproject

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.equalsTo
import com.intellij.openapi.observable.util.trim
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.*
import com.zoho.dzide.settings.ZideSettingsState
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities

class ZideNewProjectWizardStep(parentStep: NewProjectWizardStep) : AbstractNewProjectWizardStep(parentStep) {

    private val jdkProperty: GraphProperty<String> = propertyGraph.property("")
    private val serviceProperty: GraphProperty<String> = propertyGraph.property("")
    private val myServicesOnlyProperty: GraphProperty<Boolean> = propertyGraph.property(true)
    private val branchProperty: GraphProperty<String> = propertyGraph.property("master")
    private val buildTypeProperty: GraphProperty<String> = propertyGraph.property("remote")
    private val buildUrlProperty: GraphProperty<String> = propertyGraph.property("")
    private val localBuildPathProperty: GraphProperty<String> = propertyGraph.property("")

    private var products = listOf<CmToolApiClient.Product>()
    private val serviceModel = DefaultComboBoxModel<String>()
    private val jdkModel = DefaultComboBoxModel<String>()

    override fun setupUI(builder: Panel) {
        loadProducts()
        loadJdks()

        builder.apply {
            row("JDK:") {
                comboBox(jdkModel)
                    .bindItem(jdkProperty)
                    .columns(COLUMNS_MEDIUM)
            }

            row("Service:") {
                comboBox(serviceModel)
                    .bindItem(serviceProperty)
                    .columns(COLUMNS_MEDIUM)
                    .validationOnInput { combo ->
                        val selected = combo.selectedItem as? String ?: ""
                        if (selected.isBlank() || selected == "Loading..." || selected == "Connection failed") {
                            ValidationInfo("Service is required", combo)
                        } else null
                    }
                checkBox("My services only")
                    .bindSelected(myServicesOnlyProperty)
                    .onChanged { loadProducts() }
            }
            row("") {
                comment("Select the service for which you want to create the project")
            }

            separator()

            row("Branch:") {
                textField()
                    .bindText(branchProperty)
                    .columns(COLUMNS_MEDIUM)
            }
            row("") {
                comment("Branch to clone from the repository")
            }

            separator()

            row("Build Type:") {
                segmentedButton(listOf("remote", "local")) { text = it.replaceFirstChar { c -> c.uppercase() } }
                    .bind(buildTypeProperty)
            }

            row("Build URL:") {
                textField()
                    .bindText(buildUrlProperty)
                    .columns(COLUMNS_LARGE)
                    .validationOnInput { field ->
                        if (buildTypeProperty.get() == "remote" && field.text.isBlank()) {
                            ValidationInfo("Build URL is required for remote builds", field)
                        } else null
                    }
            }.visibleIf(buildTypeProperty.equalsTo("remote"))

            row("Build File:") {
                textFieldWithBrowseButton(
                    "Select Build Zip",
                    context.project,
                    FileChooserDescriptorFactory.createSingleFileDescriptor("zip")
                )
                    .bindText(localBuildPathProperty)
                    .columns(COLUMNS_LARGE)
                    .validationOnInput { field ->
                        if (buildTypeProperty.get() == "local" && field.text.isBlank()) {
                            ValidationInfo("Build file is required for local builds", field)
                        } else null
                    }
            }.visibleIf(buildTypeProperty.equalsTo("local"))


        }
    }

    override fun setupProject(project: Project) {
        val baseData = data.getUserData(NewProjectWizardBaseData.KEY) ?: return
        val projectName = baseData.name
        val projectPath = baseData.path

        if (!ZideProjectCreator.ensureCmToolToken()) return

        val selectedProduct = products.find { it.name == serviceProperty.get() }
        val selectedJdkHome = resolveJdkHomePath(jdkProperty.get())

        val wizardResult = ZideProjectWizardDialog.WizardResult(
            name = projectName,
            location = projectPath,
            jdk = jdkProperty.get(),
            jdkHomePath = selectedJdkHome,
            branch = branchProperty.get(),
            buildType = buildTypeProperty.get(),
            buildUrl = buildUrlProperty.get(),
            localBuildPath = localBuildPathProperty.get(),
            repositoryUrl = selectedProduct?.repositoryUrl ?: "",
            serviceName = selectedProduct?.name ?: "",
            downloadUrl = selectedProduct?.downloadUrl ?: "",

        )

        val creator = ZideProjectCreator(wizardResult)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating ZIDE Project: $projectName", true) {
            override fun run(indicator: ProgressIndicator) {
                creator.create(indicator)
            }
        })
    }

    private fun loadProducts() {
        val token = ZideSettingsState.getInstance().cmToolAuthToken
        if (token.isBlank()) {
            serviceModel.removeAllElements()
            serviceModel.addElement("CMTool token not configured")
            return
        }

        val personalOnly = myServicesOnlyProperty.get()
        serviceModel.removeAllElements()
        serviceModel.addElement("Loading...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fetched = CmToolApiClient.fetchProducts(token, personalOnly)
                SwingUtilities.invokeLater {
                    products = fetched
                    serviceModel.removeAllElements()
                    for (product in fetched) {
                        serviceModel.addElement(product.name)
                    }
                }
            } catch (_: Exception) {
                SwingUtilities.invokeLater {
                    serviceModel.removeAllElements()
                    serviceModel.addElement("Connection failed")
                }
            }
        }
    }

    private fun loadJdks() {
        jdkModel.removeAllElements()
        val jdks = ProjectJdkTable.getInstance().allJdks
        for (jdk in jdks) {
            jdkModel.addElement("${jdk.name} (${jdk.homePath ?: "unknown"})")
        }

        if (jdkModel.size == 0) {
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            if (!javaHome.isNullOrBlank()) {
                jdkModel.addElement("System JDK ($javaHome)")
            }
        }

        if (jdkModel.size == 0) {
            jdkModel.addElement("No JDK configured")
        }
    }

    private fun resolveJdkHomePath(comboText: String): String {
        val jdks = ProjectJdkTable.getInstance().allJdks
        for (jdk in jdks) {
            val display = "${jdk.name} (${jdk.homePath ?: "unknown"})"
            if (display == comboText) return jdk.homePath ?: ""
        }
        return ""
    }
}
