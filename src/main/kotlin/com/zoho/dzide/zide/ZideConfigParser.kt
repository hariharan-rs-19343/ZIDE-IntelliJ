package com.zoho.dzide.zide

import com.zoho.dzide.model.ZideConfigReadResult
import com.zoho.dzide.model.ZidePropertiesResult
import com.zoho.dzide.model.ZideService
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

object ZideConfigParser {

    private data class InMemoryCacheEntry(
        val serviceMtimeMs: Long,
        val propertiesMtimeMs: Long,
        val data: ZideConfigReadResult
    )

    private val inMemoryCache = ConcurrentHashMap<String, InMemoryCacheEntry>()

    fun computeCombinedHash(serviceXmlContent: String, propertiesXmlContent: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(serviceXmlContent.toByteArray(Charsets.UTF_8))
        digest.update("\n::DZIDE::\n".toByteArray(Charsets.UTF_8))
        digest.update(propertiesXmlContent.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun parseServiceXml(xmlContent: String): List<ZideService> {
        val services = mutableListOf<ZideService>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            // Prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlContent.byteInputStream())
            val serviceNodes = doc.getElementsByTagName("service")
            for (i in 0 until serviceNodes.length) {
                val serviceElement = serviceNodes.item(i) as? Element ?: continue
                val key = serviceElement.getAttribute("key") ?: continue
                val properties = extractPropertiesFromElement(serviceElement)
                services.add(ZideService(key, properties))
            }
        } catch (_: Exception) {
            // Fall back to regex parsing if DOM fails
            return parseServiceXmlRegex(xmlContent)
        }
        return services
    }

    private fun extractPropertiesFromElement(element: Element): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        val propertyNodes = element.getElementsByTagName("property")
        for (i in 0 until propertyNodes.length) {
            val prop = propertyNodes.item(i) as? Element ?: continue
            val name = prop.getAttribute("name") ?: continue
            val value = prop.getAttribute("value") ?: ""
            properties[name] = value
        }
        return properties
    }

    private fun parseServiceXmlRegex(xmlContent: String): List<ZideService> {
        val services = mutableListOf<ZideService>()
        val serviceRegex = Regex("""<service\s+key="([^"]+)"[^>]*>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL)
        val propertyRegex = Regex("""<property\s+name="([^"]+)"\s+value="([^"]*)"""")
        for (match in serviceRegex.findAll(xmlContent)) {
            val key = match.groupValues[1]
            val content = match.groupValues[2]
            val properties = mutableMapOf<String, String>()
            for (propMatch in propertyRegex.findAll(content)) {
                properties[propMatch.groupValues[1]] = propMatch.groupValues[2]
            }
            services.add(ZideService(key, properties))
        }
        return services
    }

    fun parsePropertiesXml(xmlContent: String, serviceKey: String? = null): ZidePropertiesResult? {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlContent.byteInputStream())
            val serviceNodes = doc.getElementsByTagName("service")
            for (i in 0 until serviceNodes.length) {
                val element = serviceNodes.item(i) as? Element ?: continue
                val key = element.getAttribute("key") ?: continue
                if (serviceKey == null || key == serviceKey) {
                    val properties = extractPropertiesFromElement(element)
                    return ZidePropertiesResult(key, properties)
                }
            }
        } catch (_: Exception) {
            // Fall back to regex
            return parsePropertiesXmlRegex(xmlContent, serviceKey)
        }
        return null
    }

    private fun parsePropertiesXmlRegex(xmlContent: String, serviceKey: String?): ZidePropertiesResult? {
        val serviceRegex = Regex("""<service\s+key="([^"]+)"[^>]*>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL)
        val propertyRegex = Regex("""<property\s+name="([^"]+)"\s+value="([^"]*)"""")
        for (match in serviceRegex.findAll(xmlContent)) {
            val key = match.groupValues[1]
            if (serviceKey == null || key == serviceKey) {
                val content = match.groupValues[2]
                val properties = mutableMapOf<String, String>()
                for (propMatch in propertyRegex.findAll(content)) {
                    properties[propMatch.groupValues[1]] = propMatch.groupValues[2]
                }
                return ZidePropertiesResult(key, properties)
            }
        }
        return null
    }

    fun readZideConfig(projectPath: String): ZideConfigReadResult? {
        val zideResourcesPath = Path.of(projectPath, ".zide_resources")
        val serviceXmlPath = zideResourcesPath.resolve("service.xml")
        val propertiesXmlPath = zideResourcesPath.resolve("zide_properties.xml")

        if (!serviceXmlPath.exists() || !propertiesXmlPath.exists()) return null

        try {
            val serviceMtimeMs = serviceXmlPath.getLastModifiedTime().toMillis()
            val propertiesMtimeMs = propertiesXmlPath.getLastModifiedTime().toMillis()

            val cached = inMemoryCache[projectPath]
            if (cached != null &&
                cached.serviceMtimeMs == serviceMtimeMs &&
                cached.propertiesMtimeMs == propertiesMtimeMs
            ) {
                return cached.data
            }

            val serviceXmlContent = serviceXmlPath.readText()
            val propertiesXmlContent = propertiesXmlPath.readText()

            val services = parseServiceXml(serviceXmlContent)
            val serviceOptions = services.map { it.key }

            val primaryService = services.find { it.key == "ROOT" } ?: services.firstOrNull() ?: return null
            val serviceKeyForProperties = primaryService.properties["ZIDE.SERVICE_KEY"] ?: primaryService.key
            val propertiesConfig = parsePropertiesXml(propertiesXmlContent, serviceKeyForProperties)
                ?: parsePropertiesXml(propertiesXmlContent)

            val result = ZideConfigReadResult(
                services = services,
                service = primaryService,
                properties = propertiesConfig,
                serviceOptions = serviceOptions
            )

            inMemoryCache[projectPath] = InMemoryCacheEntry(serviceMtimeMs, propertiesMtimeMs, result)
            return result
        } catch (_: Exception) {
            return null
        }
    }

    fun extractTomcatPath(serviceConfig: ZideService): String? {
        val deploymentFolder = serviceConfig.properties["ZIDE.DEPLOYMENT_FOLDER"] ?: return null
        return Path.of(deploymentFolder, "AdventNet", "Sas", "tomcat").toString()
    }

    fun extractHttpPort(propertiesConfig: ZidePropertiesResult): Int {
        val portStr = propertiesConfig.properties["ZIDE.HTTP_PORT"]
        val port = portStr?.toIntOrNull() ?: 8080
        return if (port > 0) port else 8080
    }

    fun extractHttpsPort(propertiesConfig: ZidePropertiesResult): Int? {
        val portStr = propertiesConfig.properties["ZIDE.HTTPS_PORT"] ?: return null
        return portStr.toIntOrNull()
    }

    fun extractTomcatVersion(serviceConfig: ZideService): String? =
        serviceConfig.properties["ZIDE.TOMCAT_VERSION"]

    fun extractServiceKey(serviceConfig: ZideService): String =
        serviceConfig.properties["ZIDE.SERVICE_KEY"] ?: serviceConfig.key

    fun detectZideConfigInProject(projectPath: String): Boolean {
        val zideResourcesPath = Path.of(projectPath, ".zide_resources")
        return zideResourcesPath.resolve("service.xml").exists() &&
                zideResourcesPath.resolve("zide_properties.xml").exists()
    }

    fun writePropertiesToXml(projectPath: String, serviceKey: String, updates: Map<String, String>) {
        val propertiesXmlPath = Path.of(projectPath, ".zide_resources", "zide_properties.xml")
        if (!propertiesXmlPath.exists()) return

        var content = propertiesXmlPath.readText()
        for ((name, value) in updates) {
            val regex = Regex("""(<property\s+name="${Regex.escape(name)}"\s+value=")[^"]*("\s*/>)""")
            content = regex.replace(content, "$1${Regex.escapeReplacement(value)}$2")
        }
        propertiesXmlPath.writeText(content)
        inMemoryCache.remove(projectPath)
    }
}
