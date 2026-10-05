package com.zoho.dzide.config.replacer

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Eclipse AbstractReplacer.replace: `\1`..`\9` are capture groups.
 * Unresolved `{ZIDE.` placeholders are left untouched.
 */
object EclipseReplacement {

    fun apply(content: String, regex: String, replacement: String): String {
        if (replacement.contains("{ZIDE.")) return content
        return try {
            val matcher = Pattern.compile(regex).matcher(content)
            val buffer = StringBuffer()
            val groups = groupNumbers(replacement)
            while (matcher.find()) {
                var expanded = replacement
                for (group in groups) {
                    val captured = matcher.group(group)
                    if (!captured.isNullOrEmpty()) {
                        expanded = expanded.replace("\\$group", captured)
                    }
                }
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(expanded))
            }
            matcher.appendTail(buffer)
            buffer.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun groupNumbers(value: String): List<Int> {
        val groups = mutableListOf<Int>()
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length && value[index + 1].isDigit()) {
                groups.add(value[index + 1].digitToInt())
                index += 2
            } else {
                index++
            }
        }
        return groups.distinct()
    }
}
