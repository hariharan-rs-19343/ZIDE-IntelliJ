package com.zoho.dzide.config.replacer

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses product install.xml configurations (Eclipse ZideParser subset).
 *
 * Structure:
 * ```
 * <configurations>
 *   <configuration branch="default">
 *     <file path="relative/path" type="text|xml">
 *       <property regex="..." xpath="..." append="true"><value>...</value></property>
 *     </file>
 *   </configuration>
 * </configurations>
 * ```
 */
class InstallXmlParser(installXml: File, private val properties: Map<String, String>) {

    private val document = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(installXml)

    private val fileVsProperties = LinkedHashMap<String, MutableList<Map<String, String>>>()
    private val configVsExtends = mutableMapOf<String, List<String>>()

    fun isConfigurationExists(branch: String): Boolean {
        return findConfiguration(branch) != null
    }

    fun parse(branch: String): Map<String, List<Map<String, String>>> {
        fileVsProperties.clear()
        configVsExtends.clear()
        try {
            setProperties(branch)
        } catch (_: Exception) {
            if (branch != "default") {
                fileVsProperties.clear()
                configVsExtends.clear()
                setProperties("default")
            }
        }
        return fileVsProperties.mapValues { it.value.toList() }
    }

    private fun findConfiguration(branch: String): Element? {
        val nodes = document.documentElement?.getElementsByTagName("configuration") ?: return null
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.getAttribute("branch") == branch) return el
        }
        return null
    }

    private fun setProperties(configuration: String) {
        val configObject = findConfiguration(configuration)
            ?: throw IllegalArgumentException("Configuration $configuration not found")
        if (isCyclicInheritance(configuration)) throw IllegalStateException("Cyclic inheritance")

        val attr = configObject.getAttribute("extends")
        if (attr.isNotBlank()) {
            val parents = attr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            configVsExtends[configuration] = parents
            parents.forEach { setProperties(it) }
        } else {
            configVsExtends[configuration] = emptyList()
        }
        setProperties(configObject)
    }

    private fun isCyclicInheritance(configuration: String): Boolean {
        val parents = configVsExtends[configuration] ?: return false
        return parents.any { parent ->
            configVsExtends[parent]?.contains(configuration) == true
        }
    }

    private fun setProperties(configObject: Element) {
        val name = configObject.getAttribute("branch")
        val files = configObject.getElementsByTagName("file")
        for (i in 0 until files.length) {
            val file = files.item(i) as? Element ?: continue
            val type = file.getAttribute("type").ifBlank { "text" }
            val path = file.getAttribute("path")
            val conditions = file.getElementsByTagName("if")
            if (conditions.length < 1) {
                setElementProperties(file, name, path, type)
            } else {
                for (k in 0 until conditions.length) {
                    val condition = conditions.item(k) as? Element ?: continue
                    if (checkPropertyValues(condition)) {
                        setElementProperties(condition, name, path, type)
                    }
                }
            }
        }
    }

    private fun checkPropertyValues(condition: Element): Boolean {
        val key = condition.getAttribute("key")
        if (!properties.containsKey(key)) return false
        val expected = condition.getAttribute("value")
        val actual = properties[key]
        return when (condition.getAttribute("condition")) {
            "==" -> actual == expected
            "!=" -> actual != expected
            else -> false
        }
    }

    private fun setElementProperties(configElement: Element, name: String, path: String, type: String) {
        val props = mutableListOf<Map<String, String>>()
        val propertyNodes = configElement.getElementsByTagName("property")
        for (j in 0 until propertyNodes.length) {
            val property = propertyNodes.item(j) as? Element ?: continue
            // Skip nested properties under other files when walking from configuration
            if (property.parentNode != configElement &&
                (property.parentNode as? Element)?.tagName !in setOf("file", "if")
            ) continue
            props.add(getMap(property))
        }
        for (filePathRaw in path.split(",")) {
            var filePath = replacePlaceHolders(filePathRaw.trim())
            val key = "$filePath:$type"
            fileVsProperties.getOrPut(key) { mutableListOf() }.addAll(props)
        }
    }

    private fun getMap(property: Element): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val attrs = property.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            out[attr.nodeName] = attr.nodeValue
        }
        out["replace"] = getValue(property)
        return out
    }

    private fun getValue(parent: Element): String {
        val valueNodes = parent.getElementsByTagName("value")
        if (valueNodes.length == 0) return replacePlaceHolders(parent.getAttribute("value") ?: "")
        val e = valueNodes.item(0) as? Element ?: return ""
        val sb = StringBuilder()
        val children = e.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE || child.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                val text = child.textContent?.trim().orEmpty()
                if (text.isNotEmpty()) sb.append(text)
            }
        }
        return replacePlaceHolders(sb.toString().trim())
    }

    private fun replacePlaceHolders(input: String): String {
        var out = input
        val regex = Regex("""\{([A-Za-z0-9._]+)\}""")
        regex.findAll(input).forEach { match ->
            val key = match.groupValues[1]
            val value = properties[key]
            if (value != null) {
                out = out.replace(match.value, value)
            }
        }
        return out
    }
}
