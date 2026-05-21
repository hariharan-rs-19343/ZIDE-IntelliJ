package com.zoho.dzide.newproject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.zoho.dzide.settings.ZideSettingsState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ZideProjectWizardDialog(
    private val project: Project?
) : DialogWrapper(project, true) {

    private val nameField = JBTextField("untitled")
    private val locationField = TextFieldWithBrowseButton()
    private val locationHint = JBLabel()
    private val jdkCombo = ComboBox<String>()
    private val serviceCombo = ComboBox<String>()
    private val myServicesCheckBox = JBCheckBox("My services only", true)
    private val vcsCombo = ComboBox(arrayOf("Git"))
    private val branchField = JBTextField("master")
    private val remoteBuildRadio = JRadioButton("Remote Build", true)
    private val localBuildRadio = JRadioButton("Local Build")
    private val buildUrlField = JBTextField()
    private val buildFileField = TextFieldWithBrowseButton()
    private val dependServicesField = JBTextField()
    private val serviceErrorLabel = JBLabel("Service is required").apply { foreground = JBColor.RED; isVisible = false }
    private val buildErrorLabel = JBLabel("Build URL or file is required").apply { foreground = JBColor.RED; isVisible = false }

    private var products = listOf<CmToolApiClient.Product>()

    init {
        title = "New ZIDE Project"
        setOKButtonText("Create")
        init()
        loadProducts()
    }

    override fun createCenterPanel(): JComponent {
        locationField.addBrowseFolderListener(
            "Select Project Location",
            "Choose the directory where the project will be created",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        locationField.text = resolveDefaultLocation()

        populateJdkList()

        updateLocationHint()

        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateLocationHint()
            override fun removeUpdate(e: DocumentEvent?) = updateLocationHint()
            override fun changedUpdate(e: DocumentEvent?) = updateLocationHint()
        })
        locationField.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateLocationHint()
            override fun removeUpdate(e: DocumentEvent?) = updateLocationHint()
            override fun changedUpdate(e: DocumentEvent?) = updateLocationHint()
        })

        myServicesCheckBox.addActionListener { loadProducts() }

        serviceCombo.addActionListener { }

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(8, 8, 4, 8)
        }

        var row = 0

        // Name
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(nameField, gbc)
        row++

        // Location
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        panel.add(JLabel("Location:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(locationField, gbc)
        row++

        // Location hint
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        panel.add(locationHint, gbc)
        row++

        // Separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.insets = Insets(4, 8, 4, 8)
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        row++

        // JDK
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 8, 8)
        panel.add(JLabel("JDK:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        jdkCombo.maximumSize = Dimension(Int.MAX_VALUE, jdkCombo.preferredSize.height)
        panel.add(jdkCombo, gbc)
        row++

        // Service
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        panel.add(JLabel("Service:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        serviceCombo.preferredSize = Dimension(250, serviceCombo.preferredSize.height)
        val servicePanel = JPanel(BorderLayout(8, 0)).apply {
            add(serviceCombo, BorderLayout.CENTER)
            add(myServicesCheckBox, BorderLayout.EAST)
        }
        panel.add(servicePanel, gbc)
        row++

        // Service error + hint
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        val serviceHintPanel = JPanel(BorderLayout()).apply {
            add(serviceErrorLabel, BorderLayout.NORTH)
            add(JBLabel("<html><small>Select the service for which you want to create the project</small></html>"), BorderLayout.SOUTH)
        }
        panel.add(serviceHintPanel, gbc)
        row++

        // Dependent Services
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        panel.add(JLabel("Dependencies:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(dependServicesField, gbc)
        row++

        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        panel.add(JBLabel("<html><small>Comma-separated dependent service names (e.g. ZOHOACCOUNTS). Optional.</small></html>"), gbc)
        row++

        // Separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.insets = Insets(4, 8, 4, 8)
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        row++

        // VCS
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        panel.add(JLabel("VCS:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        vcsCombo.preferredSize = Dimension(120, vcsCombo.preferredSize.height)
        val vcsWrapper = JPanel(BorderLayout()).apply { add(vcsCombo, BorderLayout.WEST) }
        panel.add(vcsWrapper, gbc)
        row++

        // VCS hint
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        panel.add(JBLabel("<html><small>Version control system used for cloning the repository</small></html>"), gbc)
        row++

        // Branch
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        panel.add(JLabel("Branch:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(branchField, gbc)
        row++

        // Branch hint
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        panel.add(JBLabel("<html><small>Select the branch for which you want to create the project</small></html>"), gbc)
        row++

        // Separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.insets = Insets(4, 8, 4, 8)
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        row++

        // Build Type radio buttons
        val buildTypeGroup = ButtonGroup()
        buildTypeGroup.add(remoteBuildRadio)
        buildTypeGroup.add(localBuildRadio)

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 4, 8)
        panel.add(JLabel("Build Type:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val buildTypePanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(remoteBuildRadio)
            add(javax.swing.Box.createHorizontalStrut(12))
            add(localBuildRadio)
        }
        panel.add(buildTypePanel, gbc)
        row++

        // Build URL (visible for Remote)
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        val buildUrlLabel = JLabel("Build URL:")
        panel.add(buildUrlLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(buildUrlField, gbc)
        row++

        // Build File (visible for Local)
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        gbc.insets = Insets(8, 8, 2, 8)
        val buildFileLabel = JLabel("Build File:")
        panel.add(buildFileLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        buildFileField.addBrowseFolderListener(
            "Select Build Zip",
            "Choose a .zip build file",
            project,
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("zip")
        )
        panel.add(buildFileField, gbc)
        row++

        // Build error
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        panel.add(buildErrorLabel, gbc)
        row++

        // Toggle visibility based on radio selection
        buildFileLabel.isVisible = false
        buildFileField.isVisible = false
        remoteBuildRadio.addActionListener {
            buildUrlLabel.isVisible = true; buildUrlField.isVisible = true
            buildFileLabel.isVisible = false; buildFileField.isVisible = false
        }
        localBuildRadio.addActionListener {
            buildUrlLabel.isVisible = false; buildUrlField.isVisible = false
            buildFileLabel.isVisible = true; buildFileField.isVisible = true
        }

        // Filler
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        panel.preferredSize = Dimension(550, panel.preferredSize.height)
        return panel
    }

    private fun loadProducts() {
        val token = ZideSettingsState.getInstance().cmToolAuthToken
        if (token.isBlank()) return

        val personalOnly = myServicesCheckBox.isSelected
        serviceCombo.removeAllItems()
        serviceCombo.addItem("Loading...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fetched = CmToolApiClient.fetchProducts(token, personalOnly)
                SwingUtilities.invokeLater {
                    products = fetched
                    serviceCombo.removeAllItems()
                    for (product in fetched) {
                        serviceCombo.addItem(product.name)
                    }
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    serviceCombo.removeAllItems()
                    serviceCombo.addItem("Connection failed")
                    serviceErrorLabel.text = "Connection failed. Please connect to the Zoho Corporation network or use FortiClient VPN to proceed."
                    serviceErrorLabel.isVisible = true
                }
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val selectedService = serviceCombo.selectedItem as? String ?: ""
        if (selectedService.isBlank() || selectedService == "Loading..." || selectedService == "Failed to load services") {
            serviceErrorLabel.isVisible = true
            return ValidationInfo("Service is required", serviceCombo)
        }
        serviceErrorLabel.isVisible = false

        if (remoteBuildRadio.isSelected && buildUrlField.text.trim().isBlank()) {
            buildErrorLabel.text = "Build URL is required"
            buildErrorLabel.isVisible = true
            return ValidationInfo("Build URL is required", buildUrlField)
        }
        if (localBuildRadio.isSelected && buildFileField.text.trim().isBlank()) {
            buildErrorLabel.text = "Build file is required"
            buildErrorLabel.isVisible = true
            return ValidationInfo("Build file is required", buildFileField)
        }
        buildErrorLabel.isVisible = false

        return null
    }

    private fun updateLocationHint() {
        val loc = locationField.text.trim()
        val name = nameField.text.trim().ifEmpty { "untitled" }
        locationHint.text = "<html><small>Project will be created in: $loc/$name</small></html>"
    }

    private fun resolveDefaultLocation(): String {
        val eclipseWorkspace = File(System.getProperty("user.home"), "eclipse-workspace")
        if (eclipseWorkspace.exists() && eclipseWorkspace.isDirectory) {
            return eclipseWorkspace.absolutePath
        }
        return System.getProperty("user.home")
    }

    private fun populateJdkList() {
        val jdks = ProjectJdkTable.getInstance().allJdks
        for (jdk in jdks) {
            jdkCombo.addItem("${jdk.name} (${jdk.homePath ?: "unknown"})")
        }
        if (jdks.isEmpty()) {
            jdkCombo.addItem("No JDK configured")
        }
    }

    fun getSelectedProduct(): CmToolApiClient.Product? {
        val selectedName = serviceCombo.selectedItem as? String ?: return null
        return products.find { it.name == selectedName }
    }

    data class WizardResult(
        val name: String,
        val location: String,
        val jdk: String,
        val jdkHomePath: String,
        val branch: String,
        val buildType: String,
        val buildUrl: String,
        val localBuildPath: String,
        val repositoryUrl: String,
        val serviceName: String,
        val downloadUrl: String,
        val dependServices: String
    )

    fun getResult(): WizardResult {
        val product = getSelectedProduct()
        val selectedJdkName = jdkCombo.selectedItem as? String ?: ""
        val jdkHome = resolveJdkHomePath(selectedJdkName)
        return WizardResult(
            name = nameField.text.trim(),
            location = locationField.text.trim(),
            jdk = selectedJdkName,
            jdkHomePath = jdkHome,
            branch = branchField.text.trim(),
            buildType = if (remoteBuildRadio.isSelected) "remote" else "local",
            buildUrl = buildUrlField.text.trim(),
            localBuildPath = buildFileField.text.trim(),
            repositoryUrl = product?.repositoryUrl ?: "",
            serviceName = product?.serviceName ?: "",
            downloadUrl = product?.downloadUrl ?: "",
            dependServices = dependServicesField.text.trim()
        )
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
