package com.zoho.dzide.vcs

import com.intellij.openapi.diagnostic.Logger
import com.zoho.dzide.settings.ZideSettingsState
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LsRemoteCommand
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

object GitService {

    private val log = Logger.getInstance(GitService::class.java)

    fun branchExists(repoUrl: String, branchName: String): Boolean {
        return try {
            val refs = lsRemote(repoUrl)
            val fullRef = "refs/heads/$branchName"
            refs.any { it == fullRef }
        } catch (e: Exception) {
            log.warn("[GitService] Failed to check branch '$branchName' in $repoUrl: ${e.message}")
            throw e
        }
    }

    fun listRemoteBranches(repoUrl: String): List<String> {
        return try {
            lsRemote(repoUrl)
                .filter { it.startsWith("refs/heads/") }
                .map { it.removePrefix("refs/heads/") }
                .sorted()
        } catch (e: Exception) {
            log.warn("[GitService] Failed to list branches from $repoUrl: ${e.message}")
            emptyList()
        }
    }

    private fun lsRemote(repoUrl: String): List<String> {
        val cmd: LsRemoteCommand = Git.lsRemoteRepository()
            .setRemote(repoUrl)
            .setHeads(true)

        val settings = ZideSettingsState.getInstance()
        val gitUsername = settings.gitUsername
        val gitPassword = settings.getPassword(ZideSettingsState.KEY_GIT_PASSWORD)

        if (gitUsername.isNotBlank() && gitPassword != null && gitPassword.isNotBlank()) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(gitUsername, gitPassword))
        }

        return cmd.call().map { it.name }
    }
}
