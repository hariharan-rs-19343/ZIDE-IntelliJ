package com.zoho.dzide.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil

/** Small label under a credential page telling which source (Settings or global) will be used. */
internal object GlobalCredentialHint {

    fun createLabel(): JBLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }

    /**
     * @param fieldsFilled whether the page's credential fields are filled (Settings value wins)
     * @param globalSource where the global credential was found, or null
     * @param lookedIn human-readable list of global sources, e.g. "~/.wgetrc or ~/.netrc"
     * @param mandatory red text when neither Settings nor global has a value
     */
    fun update(label: JBLabel?, fieldsFilled: Boolean, globalSource: String?, lookedIn: String, mandatory: Boolean) {
        label ?: return
        label.foreground = UIUtil.getContextHelpForeground()
        label.text = when {
            fieldsFilled -> "Using the value above (overrides $lookedIn)."
            globalSource != null -> "Field blank — using global credential from $globalSource."
            mandatory -> {
                label.foreground = JBColor.RED
                "Required: fill in the fields above, or add a credential to $lookedIn."
            }
            else -> "Leave blank to use $lookedIn."
        }
    }
}
