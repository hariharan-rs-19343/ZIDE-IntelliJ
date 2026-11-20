package com.zoho.dzide.config.replacer

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Eclipse ZIDE XMLReplacer parity.
 *
 * For each property (xpath required):
 * - `newAttribute="true"`: value `key=val` is set as an attribute on the xpath node.
 * - `regex` set: the xpath node is serialized, the regex replacement is applied to that
 *   string (attributes included), and the result replaces the node. A result starting with
 *   `<!--` becomes a comment; an empty result removes the node.
 * - no regex: the value is parsed as an element and appended as a child unless an identical
 *   child already exists.
 * Only the first match is touched unless `multiple="true"`.
 */
object XmlReplacer {

    fun replace(properties: List<Map<String, String>>, file: File) {
        val builder = newBuilder()
        val document = builder.parse(file)
        val xpath = XPathFactory.newInstance().newXPath()

        for (property in properties) {
            val value = property["replace"] ?: continue
            if (value.contains("{ZIDE.")) continue
            val xpathExpr = property["xpath"]?.takeIf { it.isNotBlank() } ?: continue
            try {
                if (property["newAttribute"].toBoolean()) {
                    val node = xpath.evaluate(xpathExpr, document, XPathConstants.NODE) as? Element ?: continue
                    addNewAttribute(node, value)
                    continue
                }
                val nodes = xpath.evaluate(xpathExpr, document, XPathConstants.NODESET) as? NodeList ?: continue
                val multiple = property["multiple"] == "true"
                // Snapshot first: replacing nodes mutates the live list.
                val targets = (0 until nodes.length).map { nodes.item(it) }
                for (node in if (multiple) targets else targets.take(1)) {
                    replaceNode(builder, document, node, property["regex"], value)
                }
            } catch (_: Exception) {
                // Skip invalid xpath / regex / fragment entries, like Eclipse.
            }
        }
        writeDocument(document, file)
    }

    private fun addNewAttribute(element: Element, value: String) {
        if (!value.contains("=")) return
        val key = value.substringBefore("=").trim()
        if (key.isEmpty()) return
        element.setAttribute(key, value.substringAfter("=").trim())
    }

    private fun replaceNode(builder: DocumentBuilder, document: Document, node: Node, regex: String?, value: String) {
        if (regex != null) {
            val replaced = EclipseReplacement.apply(nodeToString(node), regex, value).trim()
            val parent = node.parentNode ?: return
            when {
                replaced.startsWith("<!--") -> {
                    val text = replaced.removePrefix("<!--").removeSuffix("-->")
                    parent.replaceChild(document.createComment(text), node)
                }
                replaced.isEmpty() -> parent.removeChild(node)
                else -> parent.replaceChild(document.importNode(parseElement(builder, replaced), true), node)
            }
        } else {
            val child = document.importNode(parseElement(builder, value), true)
            val childXml = nodeToString(child)
            val children = node.childNodes
            val exists = (0 until children.length).any { nodeToString(children.item(it)) == childXml }
            if (!exists) node.appendChild(child)
        }
    }

    private fun newBuilder(): DocumentBuilder = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isNamespaceAware = false
    }.newDocumentBuilder()

    private fun parseElement(builder: DocumentBuilder, xml: String): Element =
        builder.parse(InputSource(StringReader(xml))).documentElement

    private fun nodeToString(node: Node): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(node), StreamResult(writer))
        return writer.toString()
    }

    /**
     * Writes without re-indenting: existing whitespace is kept as-is, so repeated runs are
     * byte-stable (indenting on top of existing whitespace adds blank lines every run).
     */
    private fun writeDocument(document: Document, file: File) {
        document.xmlStandalone = true
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            document.doctype?.let { doctype ->
                doctype.publicId?.let { setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, it) }
                doctype.systemId?.let { setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, it) }
            }
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        file.writeText(writer.toString())
    }
}
