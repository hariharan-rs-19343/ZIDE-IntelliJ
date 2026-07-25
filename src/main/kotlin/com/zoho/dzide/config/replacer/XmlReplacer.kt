package com.zoho.dzide.config.replacer

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

object XmlReplacer {

    fun replace(properties: List<Map<String, String>>, file: File) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(file)
        val xpath = XPathFactory.newInstance().newXPath()

        for (property in properties) {
            val replace = property["replace"] ?: continue
            if (replace.contains("{ZIDE.")) continue
            val xpathExpr = property["xpath"] ?: property["regex"] ?: continue
            try {
                val node = xpath.evaluate(xpathExpr, document, XPathConstants.NODE) as? Node ?: continue
                val regex = property["regex"]
                if (!regex.isNullOrBlank() && regex != xpathExpr) {
                    val current = node.textContent ?: ""
                    node.textContent = current.replace(Regex(regex), replace)
                } else {
                    node.textContent = replace
                }
            } catch (_: Exception) {
                // Skip invalid xpath entries
            }
        }
        writeDocument(document, file)
    }

    private fun writeDocument(document: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        file.writeText(writer.toString())
    }
}
