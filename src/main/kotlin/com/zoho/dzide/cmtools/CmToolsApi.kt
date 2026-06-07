package com.zoho.dzide.cmtools

import com.intellij.openapi.diagnostic.Logger
import com.zoho.dzide.settings.ZideSettingsState
import java.net.HttpURLConnection
import java.net.URL

data class Product(
    val id: Int,
    val name: String,
    val moduleName: String,
    val serviceName: String,
    val repositoryUrl: String,
    val downloadUrl: String
)

data class BuildLog(val url: String)

class CmToolsRequest<T>(
    private val endpoint: String,
    private val parser: (String) -> List<T>
) {
    private val params = mutableMapOf<String, String>()

    fun param(key: String, value: String): CmToolsRequest<T> {
        params[key] = value
        return this
    }

    fun send(): List<T> {
        val token = ZideSettingsState.getInstance().cmToolAuthToken
        if (token.isBlank()) {
            log.warn("[CMTools] No auth token configured")
            return emptyList()
        }

        val queryString = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        } else ""

        val url = "$BASE_URL$endpoint$queryString"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("PRIVATE-TOKEN", token)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        try {
            return when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    parser(body)
                }
                401 -> {
                    log.warn("[CMTools] Authentication failed (401). Check your PRIVATE-TOKEN.")
                    emptyList()
                }
                else -> {
                    log.warn("[CMTools] API returned ${conn.responseCode} for $endpoint")
                    emptyList()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://cmtools.csez.zohocorpin.com/api/v1"
        private val log = Logger.getInstance(CmToolsRequest::class.java)
    }
}

object CmToolsApi {

    val PRODUCTS = CmToolsRequest("/products") { json -> parseProducts(json) }

    val BUILD_LOGS = CmToolsRequest("/buildlogs") { json -> parseBuildLogs(json) }

    fun products(): CmToolsRequest<Product> = CmToolsRequest("/products") { parseProducts(it) }

    fun buildLogs(): CmToolsRequest<BuildLog> = CmToolsRequest("/buildlogs") { parseBuildLogs(it) }

    private fun parseProducts(json: String): List<Product> {
        val products = mutableListOf<Product>()
        val arrayMatch = Regex(""""products"\s*:\s*\[""").find(json) ?: return products

        forEachJsonObject(json, arrayMatch.range.last + 1) { obj ->
            val id = extractInt(obj, "id") ?: return@forEachJsonObject
            val name = extractString(obj, "name") ?: return@forEachJsonObject
            val moduleName = extractString(obj, "module_name") ?: ""
            val serviceName = extractString(obj, "service_name") ?: ""
            val repositoryUrl = extractString(obj, "repository_url") ?: ""
            val downloadUrl = extractString(obj, "download_url") ?: ""
            products.add(Product(id, name, moduleName, serviceName, repositoryUrl, downloadUrl))
        }
        return products
    }

    private fun parseBuildLogs(json: String): List<BuildLog> {
        val logs = mutableListOf<BuildLog>()
        val arrayMatch = Regex(""""buildlogs"\s*:\s*\[""").find(json) ?: return logs

        forEachJsonObject(json, arrayMatch.range.last + 1) { obj ->
            val url = extractString(obj, "url") ?: return@forEachJsonObject
            logs.add(BuildLog(url))
        }
        return logs
    }

    private fun forEachJsonObject(json: String, startIndex: Int, action: (String) -> Unit) {
        var depth = 0
        var i = startIndex
        var objectStart = -1

        while (i < json.length) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        action(json.substring(objectStart, i + 1))
                        objectStart = -1
                    }
                }
                ']' -> if (depth == 0) break
            }
            i++
        }
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int? {
        val regex = Regex(""""$key"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
}
