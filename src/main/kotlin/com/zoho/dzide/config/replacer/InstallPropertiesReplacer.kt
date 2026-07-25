package com.zoho.dzide.config.replacer

import java.io.File
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Legacy Eclipse install.properties format:
 * `relative/path #+# text|xml|template #+# regex_or_xpath #+# replacement`
 */
object InstallPropertiesReplacer {

    private val separator = Regex("""#\+#""")

    fun apply(installProperties: File, deploymentFolder: String, zideProperties: Map<String, String>): Int {
        val lines = installProperties.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val grouped = LinkedHashMap<String, MutableList<List<String>>>()
        for (line in lines) {
            val parts = separator.split(line).map { it.trim() }
            if (parts.size < 3) continue
            val fileKey = parts[0]
            grouped.getOrPut(fileKey) { mutableListOf() }.add(parts)
        }

        var touched = 0
        for ((relPath, props) in grouped) {
            val type = props.firstOrNull()?.getOrNull(1)?.lowercase() ?: "text"
            if (type.startsWith("template")) {
                val srcRel = props.firstOrNull()?.getOrNull(2) ?: continue
                val src = File(installProperties.parentFile, srcRel)
                val dest = File(deploymentFolder, relPath)
                if (src.exists()) {
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                    touched++
                }
                continue
            }

            val target = File(deploymentFolder, relPath)
            if (!target.exists()) continue

            when {
                type.startsWith("text") -> {
                    var content = target.toPath().readText()
                    for (p in props) {
                        val regex = p.getOrNull(2) ?: continue
                        val value = replacePlaceholders(p.getOrNull(3) ?: "", zideProperties)
                        if (value.contains("{ZIDE.")) continue
                        content = try {
                            content.replace(Regex(regex), value)
                        } catch (_: Exception) {
                            content
                        }
                    }
                    target.toPath().writeText(content)
                    touched++
                }
                type.startsWith("xml") -> {
                    val changes = props.map { p ->
                        val typePart = p.getOrNull(1) ?: "xml"
                        val xpath = if (':' in typePart) typePart.substringAfter(':') else (p.getOrNull(2) ?: "")
                        mapOf(
                            "xpath" to xpath,
                            "regex" to (p.getOrNull(2) ?: ""),
                            "replace" to replacePlaceholders(p.getOrNull(3) ?: "", zideProperties)
                        )
                    }
                    XmlReplacer.replace(changes, target)
                    touched++
                }
            }
        }
        return touched
    }

    private fun replacePlaceholders(input: String, props: Map<String, String>): String {
        var out = input
        Regex("""\{([A-Za-z0-9._]+)\}""").findAll(input).forEach { match ->
            val key = match.groupValues[1]
            val value = props[key]
            if (value != null) out = out.replace(match.value, value)
        }
        return out
    }
}
