package com.zoho.dzide.config.replacer

import java.io.File
import kotlin.io.path.readText
import kotlin.io.path.writeText

object TextReplacer {

    fun replace(properties: List<Map<String, String>>, file: File) {
        var content = file.toPath().readText()
        for (property in properties) {
            val replace = property["replace"] ?: continue
            if (replace.contains("{ZIDE.")) continue

            val append = property["append"]?.toBoolean() == true
            if (append) {
                val newLine = System.lineSeparator()
                val newProp = "$newLine${replace.trim()}$newLine"
                if (content.contains(newProp)) continue
                if (replace.contains("=")) {
                    val keyPrefix = replace.substring(0, replace.indexOf('=') + 1)
                    val regexLine = Regex("""(?m)^(${Regex.escape(keyPrefix)}).*$""")
                    if (regexLine.containsMatchIn(content)) {
                        content = regexLine.replace(content, "$1${Regex.escapeReplacement(replace.substringAfter('='))}")
                        continue
                    }
                }
                content += newProp
            } else {
                val regex = property["regex"] ?: continue
                content = try {
                    content.replace(Regex(regex), replace)
                } catch (_: Exception) {
                    content
                }
            }
        }
        file.toPath().writeText(content)
    }
}
