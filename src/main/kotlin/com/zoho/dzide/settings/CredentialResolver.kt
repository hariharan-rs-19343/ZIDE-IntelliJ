package com.zoho.dzide.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Resolves credentials for internal hosts: the value in Settings > Tools > Zide wins;
 * when it is blank, falls back to the global system credential.
 *
 * - CMTool  → `~/.zide/zide.conf` (`zide.cmtools.authtoken`, shared with Eclipse ZIDE)
 * - Wget    → `~/.wgetrc`, then `~/.netrc`
 * - Git     → `git credential fill` (osxkeychain, ~/.git-credentials), then `~/.netrc`
 * - Zoho repository → `~/.hgrc` `[auth]`, then `~/.netrc`
 *
 * Read-only towards the system: never writes to a credential helper or keychain.
 */
object CredentialResolver {

    private val log = Logger.getInstance(CredentialResolver::class.java)

    const val SOURCE_SETTINGS = "Settings"
    const val SOURCE_ZIDE_CONF = "~/.zide/zide.conf"
    const val SOURCE_WGETRC = "~/.wgetrc"
    const val SOURCE_NETRC = "~/.netrc"
    const val SOURCE_HGRC = "~/.hgrc"
    const val SOURCE_GIT_HELPER = "git credential helper"

    const val WGET_HOST = "build.zohocorp.com"
    const val ZIDE_CONF_TOKEN_KEY = "zide.cmtools.authtoken"

    private const val GIT_FILL_TIMEOUT_SEC = 5L

    data class ResolvedCredential(val username: String, val secret: String, val source: String) {
        val fromSettings: Boolean get() = source == SOURCE_SETTINGS
        override fun toString(): String = "ResolvedCredential(username=$username, source=$source)"
    }

    private fun userHome(): File = File(System.getProperty("user.home"))

    // ---- CMTool ----

    fun resolveCmToolToken(): ResolvedCredential? =
        resolveCmToolToken(ZideSettingsState.getInstance().cmToolAuthToken, userHome())

    fun resolveCmToolTokenGlobal(): ResolvedCredential? = resolveCmToolToken("", userHome())

    fun resolveCmToolToken(settingsToken: String, home: File): ResolvedCredential? {
        if (settingsToken.isNotBlank()) return ResolvedCredential("", settingsToken.trim(), SOURCE_SETTINGS)
        val token = readText(File(home, ".zide/zide.conf"))?.let { parseZideConf(it) } ?: return null
        return ResolvedCredential("", token, SOURCE_ZIDE_CONF)
    }

    // ---- Wget ----

    fun resolveWget(host: String = WGET_HOST): ResolvedCredential? {
        val settings = ZideSettingsState.getInstance()
        return resolveWget(
            settings.wgetUsername,
            settings.getPassword(ZideSettingsState.KEY_WGET_PASSWORD),
            userHome(),
            host
        )
    }

    fun resolveWgetGlobal(host: String = WGET_HOST): ResolvedCredential? = resolveWget("", "", userHome(), host)

    fun resolveWget(settingsUser: String, settingsPass: String, home: File, host: String = WGET_HOST): ResolvedCredential? {
        if (settingsUser.isNotBlank() && settingsPass.isNotEmpty()) {
            return ResolvedCredential(settingsUser.trim(), settingsPass, SOURCE_SETTINGS)
        }
        readText(File(home, ".wgetrc"))?.let { parseWgetrc(it) }?.let {
            return ResolvedCredential(it.first, it.second, SOURCE_WGETRC)
        }
        return fromNetrc(home, host)
    }

    /**
     * Env for a wget child process. When the credential comes from Settings, points `WGETRC` at a
     * temporary copy of `~/.wgetrc` carrying the Settings user/password, so a stale `~/.wgetrc`
     * cannot win. Otherwise wget reads `~/.wgetrc` / `~/.netrc` itself and [base] is returned as is.
     * Call the returned cleanup once the process has exited.
     */
    fun wgetEnv(base: Map<String, String>, cred: ResolvedCredential?): Pair<Map<String, String>, () -> Unit> {
        if (cred == null || !cred.fromSettings) return base to {}
        return try {
            val existing = readText(File(userHome(), ".wgetrc")).orEmpty()
            val tmp = Files.createTempFile("dzide-wgetrc-", "")
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
            }
            Files.writeString(tmp, buildWgetrc(existing, cred.username, cred.secret))
            (base + ("WGETRC" to tmp.toString())) to { Files.deleteIfExists(tmp); Unit }
        } catch (e: Exception) {
            log.warn("Could not create temporary wgetrc; wget falls back to ~/.wgetrc: ${e.message}")
            base to {}
        }
    }

    internal fun buildWgetrc(existing: String, user: String, password: String): String {
        val kept = existing.lines().filter { line ->
            val key = line.substringBefore('=', "").let(::normalizeWgetKey)
            key !in setOf("user", "password", "httpuser", "httppassword")
        }
        return (kept.joinToString("\n").trimEnd() + "\nuser=$user\npassword=$password\n").trimStart()
    }

    // ---- Git ----

    fun resolveGit(repoUrl: String, gitExe: String? = findGitExecutable()): ResolvedCredential? {
        val settings = ZideSettingsState.getInstance()
        val user = settings.gitUsername.trim()
        val pass = settings.getPassword(ZideSettingsState.KEY_GIT_PASSWORD)
        if (user.isNotEmpty() && pass.isNotEmpty()) return ResolvedCredential(user, pass, SOURCE_SETTINGS)
        return resolveGitGlobal(repoUrl, gitExe)
    }

    fun resolveGitGlobal(repoUrl: String, gitExe: String? = findGitExecutable()): ResolvedCredential? {
        val uri = parseHttpUri(repoUrl) ?: return null
        if (gitExe != null) {
            gitCredentialFill(gitExe, uri.scheme, uri.host)?.let { return it }
        }
        return fromNetrc(userHome(), uri.host)
    }

    /** Read-only query of git's configured helpers; prompts are disabled so it never blocks. */
    private fun gitCredentialFill(gitExe: String, protocol: String, host: String): ResolvedCredential? {
        return try {
            val pb = ProcessBuilder(gitExe, "credential", "fill")
            pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
            pb.environment()["GIT_ASKPASS"] = ""
            pb.environment()["SSH_ASKPASS"] = ""
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val process = pb.start()
            process.outputStream.bufferedWriter().use { it.write("protocol=$protocol\nhost=$host\n\n") }
            if (!process.waitFor(GIT_FILL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val out = process.inputStream.bufferedReader().readText()
            parseGitCredentialOutput(out)?.let { ResolvedCredential(it.first, it.second, SOURCE_GIT_HELPER) }
        } catch (e: Exception) {
            log.debug("git credential fill failed: ${e.message}")
            null
        }
    }

    fun findGitExecutable(): String? {
        val gitPath = try {
            ZideSettingsState.getInstance().gitPath
        } catch (_: Exception) {
            ""
        }
        if (gitPath.isNotBlank()) {
            val gitExe = File(gitPath, "git")
            if (gitExe.exists()) return gitExe.absolutePath
        }
        for (candidate in listOf("/usr/bin/git", "/usr/local/bin/git")) {
            if (File(candidate).exists()) return candidate
        }
        try {
            val process = ProcessBuilder("which", "git").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.isNotEmpty() && File(result).exists()) return result
        } catch (_: Exception) {}
        return null
    }

    // ---- Zoho repository ----

    fun resolveZohoRepoSettings(): ResolvedCredential? {
        val settings = ZideSettingsState.getInstance()
        val user = settings.zohoRepoUsername.trim()
        val pass = settings.getPassword(ZideSettingsState.KEY_ZOHO_REPO_PASSWORD)
        return if (user.isNotEmpty() && pass.isNotEmpty()) ResolvedCredential(user, pass, SOURCE_SETTINGS) else null
    }

    fun resolveZohoRepoGlobal(url: String, home: File = userHome()): ResolvedCredential? {
        readText(File(home, ".hgrc"))?.let { parseHgrcAuth(it, url) }?.let {
            return ResolvedCredential(it.first, it.second, SOURCE_HGRC)
        }
        val host = parseHttpUri(url)?.host ?: return null
        return fromNetrc(home, host)
    }

    fun resolveZohoRepo(url: String): ResolvedCredential? = resolveZohoRepoSettings() ?: resolveZohoRepoGlobal(url)

    // ---- Mandatory checks (EDT) ----

    /** CMTool token or null after telling the user where to configure it. */
    fun requireCmToolToken(project: Project?): String? {
        resolveCmToolToken()?.let { return it.secret }
        promptOpenSettings(
            project,
            "CMTool Auth Token is required.\n\n" +
                "Enter it in Settings > Tools > Zide > CMTool, or add\n" +
                "$ZIDE_CONF_TOKEN_KEY=<token> to $SOURCE_ZIDE_CONF.",
            "CMTool Auth Token Required",
            CmToolConfigurable::class.java
        )
        return resolveCmToolToken()?.secret
    }

    /** Wget credential or null after telling the user where to configure it. */
    fun requireWget(project: Project?): ResolvedCredential? {
        resolveWget()?.let { return it }
        promptOpenSettings(
            project,
            "Wget credential is required to download builds.\n\n" +
                "Enter it in Settings > Tools > Zide > Wget Configuration, or add\n" +
                "user/password to $SOURCE_WGETRC (or a $WGET_HOST entry to $SOURCE_NETRC).",
            "Wget Credential Required",
            WgetConfigurable::class.java
        )
        return resolveWget()
    }

    private fun promptOpenSettings(project: Project?, message: String, title: String, page: Class<out com.intellij.openapi.options.Configurable>) {
        val choice = Messages.showOkCancelDialog(project, message, title, "Open Settings", "Cancel", Messages.getErrorIcon())
        if (choice == Messages.OK) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, page)
        }
    }

    // ---- Auth failure reporting ----

    fun looksLikeAuthFailure(output: String): Boolean {
        val lower = output.lowercase()
        return listOf(
            "authentication failed",
            "username/password authentication failed",
            "invalid username or password",
            "401 unauthorized",
            "403 forbidden",
            "could not read username",
            "error 401",
            "error 403"
        ).any { it in lower }
    }

    fun authFailureMessage(tool: String, cred: ResolvedCredential?, settingsPage: String): String {
        val from = when {
            cred == null -> "no credential (Settings and global sources are empty)"
            cred.fromSettings -> "credential from Settings > Tools > Zide > $settingsPage"
            else -> "credential from ${cred.source}"
        }
        return "$tool authentication failed using $from. " +
            "Update it, or enter Username/Password in Settings > Tools > Zide > $settingsPage."
    }

    // ---- Parsers ----

    internal fun parseZideConf(text: String): String? {
        val props = Properties()
        return try {
            props.load(StringReader(text))
            props.getProperty(ZIDE_CONF_TOKEN_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** wget treats `-`/`_` in command names as insignificant; `http_user` overrides `user`. */
    internal fun parseWgetrc(text: String): Pair<String, String>? {
        val values = mutableMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) continue
            values[normalizeWgetKey(line.substringBefore('='))] = line.substringAfter('=').trim()
        }
        val user = values["httpuser"]?.takeIf { it.isNotEmpty() } ?: values["user"]
        val pass = values["httppassword"]?.takeIf { it.isNotEmpty() } ?: values["password"]
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        return user to pass
    }

    private fun normalizeWgetKey(key: String): String = key.trim().lowercase().replace("_", "").replace("-", "")

    internal fun parseNetrc(text: String, host: String): Pair<String, String>? {
        val tokens = text.lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString(" ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        var match: Pair<String, String>? = null
        var fallback: Pair<String, String>? = null
        var i = 0
        while (i < tokens.size) {
            val isMachine = tokens[i] == "machine"
            val isDefault = tokens[i] == "default"
            if (!isMachine && !isDefault) {
                i++
                continue
            }
            val name = if (isMachine) tokens.getOrNull(i + 1) else null
            i += if (isMachine) 2 else 1
            var login: String? = null
            var password: String? = null
            while (i < tokens.size && tokens[i] != "machine" && tokens[i] != "default") {
                when (tokens[i]) {
                    "login" -> { login = tokens.getOrNull(i + 1); i += 2 }
                    "password" -> { password = tokens.getOrNull(i + 1); i += 2 }
                    "account" -> i += 2
                    "macdef" -> { i = tokens.size }
                    else -> i++
                }
            }
            if (login.isNullOrEmpty() || password == null) continue
            if (isMachine && name.equals(host, ignoreCase = true) && match == null) match = login to password
            if (isDefault && fallback == null) fallback = login to password
        }
        return match ?: fallback
    }

    /** Mercurial `[auth]`: `<name>.prefix`, `<name>.username`, `<name>.password`; longest prefix wins. */
    internal fun parseHgrcAuth(text: String, url: String): Pair<String, String>? {
        val entries = mutableMapOf<String, MutableMap<String, String>>()
        var inAuth = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inAuth = line.substring(1, line.length - 1).trim() == "auth"
                continue
            }
            if (!inAuth || !line.contains('=')) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            val dot = key.lastIndexOf('.')
            if (dot <= 0) continue
            entries.getOrPut(key.substring(0, dot)) { mutableMapOf() }[key.substring(dot + 1)] = value
        }
        val target = stripScheme(url)
        return entries.values
            .mapNotNull { e ->
                val prefix = e["prefix"] ?: return@mapNotNull null
                val user = e["username"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val pass = e["password"] ?: return@mapNotNull null
                val p = stripScheme(prefix)
                if (p == "*" || target.startsWith(p)) Triple(if (p == "*") 0 else p.length, user, pass) else null
            }
            .maxByOrNull { it.first }
            ?.let { it.second to it.third }
    }

    internal fun parseGitCredentialOutput(text: String): Pair<String, String>? {
        val values = text.lines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val user = values["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = values["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return user to pass
    }

    private fun stripScheme(url: String): String = url.trim().substringAfter("://").trimEnd('/')

    private fun parseHttpUri(url: String): URI? = try {
        URI(url.trim()).takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun fromNetrc(home: File, host: String): ResolvedCredential? {
        val text = readText(File(home, ".netrc")) ?: readText(File(home, "_netrc")) ?: return null
        return parseNetrc(text, host)?.let { ResolvedCredential(it.first, it.second, SOURCE_NETRC) }
    }

    private fun readText(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null
    }
}
