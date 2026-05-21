package com.zoho.dzide.newproject

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL

object CmToolApiClient {

    private const val BASE_URL = "https://cmtools.csez.zohocorpin.com/api/v1"
    private val log = Logger.getInstance(CmToolApiClient::class.java)

    data class Product(
        val id: Int,
        val name: String,
        val repositoryUrl: String,
        val downloadUrl: String,
        val serviceName: String
    )

    fun fetchProducts(token: String, personalOnly: Boolean): List<Product> {
        val endpoint = if (personalOnly) {
            "$BASE_URL/products?personal=true&include_role_acccess=true"
        } else {
            "$BASE_URL/products"
        }

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("PRIVATE-TOKEN", token)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        try {
            if (conn.responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                log.warn("CMTool API returned ${conn.responseCode}: $errorBody")
                throw RuntimeException("CMTool API returned ${conn.responseCode}")
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            return parseProducts(responseBody)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProducts(json: String): List<Product> {
        val products = mutableListOf<Product>()
        val productsArrayMatch = Regex(""""products"\s*:\s*\[""").find(json) ?: return products

        var depth = 0
        var i = productsArrayMatch.range.last + 1
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
                        val objStr = json.substring(objectStart, i + 1)
                        parseProduct(objStr)?.let { products.add(it) }
                        objectStart = -1
                    }
                }
                ']' -> if (depth == 0) break
            }
            i++
        }
        return products
    }

    private fun parseProduct(objStr: String): Product? {
        val id = extractInt(objStr, "id") ?: return null
        val name = extractString(objStr, "name") ?: return null
        val repositoryUrl = extractString(objStr, "repository_url") ?: ""
        val downloadUrl = extractString(objStr, "download_url") ?: ""
        val serviceName = extractString(objStr, "service_name") ?: ""
        return Product(id, name, repositoryUrl, downloadUrl, serviceName)
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
