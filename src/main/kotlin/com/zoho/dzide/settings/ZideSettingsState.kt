package com.zoho.dzide.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "DzideSettings",
    storages = [Storage("dzide-settings.xml")]
)
class ZideSettingsState : PersistentStateComponent<ZideSettingsState.State> {

    data class State(
        var cmToolAuthToken: String = "",
        var wgetUsername: String = "",
        var gitPath: String = "",
        var gitUsername: String = "",
        var zohoRepoUsername: String = "",
        var customBuildUrl: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var cmToolAuthToken: String
        get() = myState.cmToolAuthToken
        set(value) { myState.cmToolAuthToken = value }

    var wgetUsername: String
        get() = myState.wgetUsername
        set(value) { myState.wgetUsername = value }

    var gitPath: String
        get() = myState.gitPath
        set(value) { myState.gitPath = value }

    var gitUsername: String
        get() = myState.gitUsername
        set(value) { myState.gitUsername = value }

    var zohoRepoUsername: String
        get() = myState.zohoRepoUsername
        set(value) { myState.zohoRepoUsername = value }

    var customBuildUrl: String
        get() = myState.customBuildUrl
        set(value) { myState.customBuildUrl = value }

    fun getPassword(key: String): String {
        val attributes = credentialAttributes(key)
        return PasswordSafe.instance.getPassword(attributes) ?: ""
    }

    fun setPassword(key: String, password: String) {
        val attributes = credentialAttributes(key)
        PasswordSafe.instance.setPassword(attributes, password.ifEmpty { null })
    }

    private fun credentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(generateServiceName("DZIDE", key))
    }

    companion object {
        const val KEY_WGET_PASSWORD = "wget_password"
        const val KEY_GIT_PASSWORD = "git_password"
        const val KEY_ZOHO_REPO_PASSWORD = "zoho_repo_password"

        fun getInstance(): ZideSettingsState =
            ApplicationManager.getApplication().getService(ZideSettingsState::class.java)
    }
}
