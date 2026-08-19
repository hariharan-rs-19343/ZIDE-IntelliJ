package com.zoho.dzide.zide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.zoho.dzide.settings.ZideSettingsState
import com.zoho.dzide.util.ProcessUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

/**
 * Clones / updates the shared Eclipse ZIDE config Mercurial repo into `{workspace}/zide`
 * (Eclipse `ServiceAPI.cloneZideProject` parity).
 */
object ZideConfigRepoService {

    const val REPO_NAME = "zide"
    const val CLONE_URL = "https://cmsuite.csez.zohocorpin.com/eclipse/zide"
    const val BRANCH = "default"
    const val CREDENTIAL_URL = "http://zide.csez.zohocorpin.com/softwares/repository/credentials"

    private const val CLONE_TIMEOUT_MS = 600_000L
    private const val PULL_TIMEOUT_MS = 300_000L
    private const val CREDENTIAL_TIMEOUT_MS = 15_000

    private val log = Logger.getInstance(ZideConfigRepoService::class.java)
    private val pathAssign = Pattern.compile("^(\\s*)(default(?:-push)?)(\\s*=\\s*)(.*?)(\\s*)$")
    private val userInfoInUrl = Regex("""(https?)://[^/\s:]+:[^/\s@]+@""", RegexOption.IGNORE_CASE)

    data class EnsureResult(
        val success: Boolean,
        val zideDir: File?,
        val message: String,
        val cloned: Boolean = false
    )

    fun resolveZideDir(workspaceDir: File): File = File(workspaceDir, REPO_NAME)

    /**
     * Clone if missing, otherwise `hg pull -u`. Create must abort when this returns [EnsureResult.success] false.
     */
    fun ensureCloned(workspaceDir: File, indicator: ProgressIndicator? = null): EnsureResult {
        val zideDir = resolveZideDir(workspaceDir)
        indicator?.text = if (zideDir.exists()) {
            "Updating ZIDE config repository..."
        } else {
            "Cloning ZIDE config repository..."
        }

        val hg = findHgExecutable()
        if (hg == null) {
            if (looksLikeZideRepo(zideDir)) {
                log.warn("hg not on PATH; using existing ${zideDir.absolutePath} without update")
                return EnsureResult(true, zideDir, "Using existing zide folder (hg not found)", cloned = false)
            }
            return EnsureResult(
                false,
                null,
                "Mercurial (hg) not found on PATH. Install Mercurial to clone the ZIDE config repository."
            )
        }

        return try {
            when {
                !zideDir.exists() -> cloneRepo(hg, zideDir, indicator)
                File(zideDir, ".hg").isDirectory -> pullRepo(hg, zideDir, indicator)
                looksLikeZideRepo(zideDir) -> {
                    log.warn("zide folder exists without .hg; using recipes as-is: ${zideDir.absolutePath}")
                    EnsureResult(true, zideDir, "Using existing zide folder (not an hg clone)", cloned = false)
                }
                else -> EnsureResult(
                    false,
                    zideDir,
                    "Folder ${zideDir.absolutePath} exists but is not a ZIDE config repository."
                )
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("ZIDE config repo clone/update failed", e)
            EnsureResult(
                false,
                zideDir.takeIf { looksLikeZideRepo(it) },
                redactUrlUserInfo(e.message ?: "Unknown error")
            )
        }
    }

    fun looksLikeZideRepo(zideDir: File): Boolean {
        if (!zideDir.isDirectory) return false
        return File(zideDir, "deployment").isDirectory || File(zideDir, ".zide_resources").isDirectory
    }

    /**
     * Catalog `@moduledir` from `{zide}/.zide_resources/zide_config.xml`.
     * Falls back to `{name}_cloud` if that deployment folder exists, else [productName].
     */
    fun resolveModuleDir(
        zideDir: File,
        productName: String,
        serviceKey: String? = null
    ): String {
        val fallback = productName.ifBlank { REPO_NAME }
        val catalogFile = File(zideDir, ".zide_resources/zide_config.xml")
        if (catalogFile.isFile) {
            val fromCatalog = parseModuleDirFromCatalog(catalogFile.readText(), fallback, serviceKey)
            if (fromCatalog != null) return fromCatalog
        }
        val cloud = "${fallback}_cloud"
        if (File(zideDir, "deployment/$cloud").isDirectory) return cloud
        return fallback
    }

    fun parseModuleDirFromCatalog(xml: String, productName: String, serviceKey: String? = null): String? {
        val candidates = linkedSetOf<String>()
        listOfNotNull(serviceKey, productName).forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                candidates.add(trimmed)
                candidates.add(trimmed.replace('-', '_'))
            }
        }
        if (candidates.isEmpty()) return null

        val serviceTag = Regex("""<service\b([^>]*)/?>""", setOf(RegexOption.IGNORE_CASE))
        for (match in serviceTag.findAll(xml)) {
            val attrs = match.groupValues[1]
            val key = attr(attrs, "key") ?: continue
            val name = attr(attrs, "name")
            val moduleDir = attr(attrs, "moduledir") ?: continue
            if (matchesCatalogService(key, name, moduleDir, candidates)) {
                return moduleDir
            }
        }
        return null
    }

    fun embedCredentials(url: String, user: String, password: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val encodedUser = urlEncode(user)
        val encodedPass = urlEncode(password)
        return url.substring(0, schemeEnd + 3) + encodedUser + ":" + encodedPass + "@" + url.substring(schemeEnd + 3)
    }

    /** Strips `user:pass@` from http(s) URLs in user-visible clone/error text. */
    fun redactUrlUserInfo(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        return userInfoInUrl.replace(text, "$1://")
    }

    fun stripUrlUserInfo(value: String): String {
        val trimmed = value.trim()
        val quoted = trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
        val inner = if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
        return try {
            val u = URI(inner)
            if (u.userInfo == null) return value
            val clean = URI(u.scheme, null, u.host, u.port, u.path, u.query, u.fragment)
            val rebuilt = clean.toASCIIString()
            if (quoted) "\"$rebuilt\"" else rebuilt
        } catch (_: Exception) {
            value
        }
    }

    fun stripUserInfoFromHgrc(repositoryRoot: File) {
        if (!repositoryRoot.isDirectory) return
        val hgrc = File(repositoryRoot, ".hg/hgrc")
        if (!hgrc.isFile) return
        try {
            val lines = Files.readAllLines(hgrc.toPath(), StandardCharsets.UTF_8)
            val out = lines.map { line ->
                val m = pathAssign.matcher(line)
                if (m.matches() && (m.group(2) == "default" || m.group(2) == "default-push")) {
                    val sanitized = stripUrlUserInfo(m.group(4).trim())
                    m.group(1) + m.group(2) + m.group(3) + sanitized + m.group(5)
                } else {
                    line
                }
            }
            Files.write(hgrc.toPath(), out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            log.warn("Could not strip credentials from ${hgrc.absolutePath}", e)
        }
    }

    private fun cloneRepo(hg: String, zideDir: File, indicator: ProgressIndicator?): EnsureResult {
        val parent = zideDir.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val authUrl = authenticatedCloneUrl()
        indicator?.text2 = "hg clone $CLONE_URL"
        val result = ProcessUtil.executeStreamingAndWait(
            command = listOf(hg, "clone", "-b", BRANCH, authUrl, zideDir.absolutePath),
            timeoutMs = CLONE_TIMEOUT_MS,
            shouldCancel = { checkCanceled(indicator) },
            onStdout = { chunk -> setProgressLine(indicator, chunk) },
            onStderr = { chunk -> setProgressLine(indicator, chunk) }
        )
        if (indicator?.isCanceled == true) {
            if (zideDir.exists()) zideDir.deleteRecursively()
            throw ProcessCanceledException()
        }
        if (result.exitCode != 0) {
            if (zideDir.exists()) zideDir.deleteRecursively()
            val detail = redactUrlUserInfo((result.stderr + result.stdout).takeLast(500))
            return EnsureResult(false, null, "hg clone failed (exit ${result.exitCode}): $detail")
        }
        stripUserInfoFromHgrc(zideDir)
        if (!looksLikeZideRepo(zideDir)) {
            return EnsureResult(false, zideDir, "Cloned zide repo is missing deployment/ recipes")
        }
        log.info("Cloned ZIDE config repo to ${zideDir.absolutePath}")
        return EnsureResult(true, zideDir, "Cloned", cloned = true)
    }

    private fun pullRepo(hg: String, zideDir: File, indicator: ProgressIndicator?): EnsureResult {
        val authUrl = authenticatedCloneUrl()
        indicator?.text2 = "hg pull -u"
        val result = ProcessUtil.executeStreamingAndWait(
            command = listOf(hg, "pull", "-u", "-b", BRANCH, authUrl),
            workingDir = zideDir.absolutePath,
            timeoutMs = PULL_TIMEOUT_MS,
            shouldCancel = { checkCanceled(indicator) },
            onStdout = { chunk -> setProgressLine(indicator, chunk) },
            onStderr = { chunk -> setProgressLine(indicator, chunk) }
        )
        if (indicator?.isCanceled == true) {
            throw ProcessCanceledException()
        }
        stripUserInfoFromHgrc(zideDir)
        if (result.exitCode != 0) {
            val detail = redactUrlUserInfo((result.stderr + result.stdout).takeLast(500))
            if (looksLikeZideRepo(zideDir)) {
                log.warn("hg pull failed; using existing zide recipes: $detail")
                return EnsureResult(true, zideDir, "Using existing zide folder (pull failed): $detail")
            }
            return EnsureResult(false, zideDir, "hg pull failed (exit ${result.exitCode}): $detail")
        }
        log.info("Updated ZIDE config repo at ${zideDir.absolutePath}")
        return EnsureResult(true, zideDir, "Updated")
    }

    private fun authenticatedCloneUrl(): String {
        val creds = fetchCloneCredentials()
        return if (creds != null) embedCredentials(CLONE_URL, creds.first, creds.second) else CLONE_URL
    }

    internal fun fetchCloneCredentials(): Pair<String, String>? {
        fetchCredentialsFromUrl()?.let { return it }
        val settings = try {
            ZideSettingsState.getInstance()
        } catch (_: Exception) {
            null
        } ?: return null
        val wgetUser = settings.wgetUsername.trim()
        val wgetPass = settings.getPassword(ZideSettingsState.KEY_WGET_PASSWORD)
        if (wgetUser.isNotEmpty()) return wgetUser to wgetPass
        val gitUser = settings.gitUsername.trim()
        val gitPass = settings.getPassword(ZideSettingsState.KEY_GIT_PASSWORD)
        if (gitUser.isNotEmpty()) return gitUser to gitPass
        return null
    }

    private fun fetchCredentialsFromUrl(): Pair<String, String>? {
        return try {
            val conn = URL(CREDENTIAL_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CREDENTIAL_TIMEOUT_MS
            conn.readTimeout = CREDENTIAL_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != 200) {
                    log.warn("ZIDE credential URL returned ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader().readText().trim()
                parseCredentialBody(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch ZIDE clone credentials: ${e.message}")
            null
        }
    }

    fun parseCredentialBody(body: String): Pair<String, String>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":", limit = 2)
        val user = parts[0].trim()
        if (user.isEmpty()) return null
        val pass = parts.getOrElse(1) { "" }
        return user to pass
    }

    private fun findHgExecutable(): String? {
        val which = if (com.zoho.dzide.util.ShellUtil.isWindows) listOf("where", "hg") else listOf("which", "hg")
        val located = ProcessUtil.executeCapturing(which, timeoutMs = 5_000)
        if (located.exitCode == 0) {
            val path = located.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (!path.isNullOrBlank()) return path
        }
        val version = ProcessUtil.executeCapturing(listOf("hg", "version"), timeoutMs = 5_000)
        return if (version.exitCode == 0) "hg" else null
    }

    private fun setProgressLine(indicator: ProgressIndicator?, chunk: String) {
        val line = chunk.lineSequence().lastOrNull()?.trim().orEmpty()
        indicator?.text2 = redactUrlUserInfo(line)
    }

    private fun checkCanceled(indicator: ProgressIndicator?): Boolean {
        if (indicator == null) return false
        return try {
            indicator.checkCanceled()
            false
        } catch (_: ProcessCanceledException) {
            true
        }
    }

    private fun attr(attrs: String, name: String): String? {
        return Regex("""\b$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
    }

    private fun matchesCatalogService(
        key: String,
        name: String?,
        moduleDir: String,
        candidates: Set<String>
    ): Boolean {
        val fields = listOfNotNull(key, name, moduleDir)
        for (candidate in candidates) {
            for (field in fields) {
                if (field.equals(candidate, ignoreCase = true)) return true
            }
            val cloud = if (candidate.endsWith("_cloud", ignoreCase = true)) candidate else "${candidate}_cloud"
            if (key.equals(cloud, ignoreCase = true) || moduleDir.equals(cloud, ignoreCase = true)) return true
        }
        return false
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
