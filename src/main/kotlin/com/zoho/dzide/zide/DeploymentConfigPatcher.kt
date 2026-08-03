package com.zoho.dzide.zide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zoho.dzide.util.NotificationUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Patches deployment config files to replicate what Eclipse ZIDE does during server setup.
 *
 * Eclipse uses pgsql_replace.xml / mysql_replace.xml (from the zide config repo) to patch:
 * 1. configuration.properties — DB driver, URL, port, vendor, credentials, schema
 * 2. persistence-configurations.xml — DBName, DSAdapter, StartDBServer
 * 3. security-properties.xml — IAM server, service name, logout page
 * 4. server.xml — restore from server.xml.orig, Context element, shutdown port, HTTPS port on existing SSL connector
 * 5. web.xml — JSP servlet for dynamic compilation
 *
 * SSL keystore and HTTPS Connector ownership: App. Server container only.
 * This plugin never downloads sas.keystore or injects a new HTTPS Connector.
 */
object DeploymentConfigPatcher {

    private val log = Logger.getInstance(DeploymentConfigPatcher::class.java)

    data class PatchContext(
        val deploymentFolder: String,
        val parentService: String,
        val iamServer: String?,
        val iamServiceName: String?,
        val hostName: String?,
        val httpsPort: String?,
        val dbType: String?,
        val dbName: String?,
        val dbUser: String?,
        val dbPass: String?,
        val dbHost: String?,
        val schemaName: String?
    ) {
        val isPgsql: Boolean get() = dbType?.uppercase() == "PGSQL"
    }

    data class PatchResult(
        val serverXmlPatched: Boolean = false,
        val webXmlPatched: Boolean = false,
        val persistencePatched: Boolean = false,
        val securityPatched: Boolean = false,
        val configPropertiesPatched: Boolean = false,
        val serverXmlRestoredFromOrig: Boolean = false,
        val httpsPortUpdated: Boolean = false,
        val keystoreMissing: Boolean = false,
        val errors: List<String> = emptyList()
    )

    fun patchAll(ctx: PatchContext, project: Project? = null): PatchResult {
        val errors = mutableListOf<String>()

        // No .orig restore (can wipe SSL). Rewrite HTTPS connector, then Context / Host patches.
        val restoredFromOrig = false
        val httpsPortUpdated = try {
            ensureHttpsConnector(ctx)
        } catch (e: Exception) {
            errors.add("HTTPS connector: ${e.message}")
            false
        }
        val serverXmlOk = try {
            patchServerXml(ctx)
        } catch (e: Exception) {
            errors.add("server.xml: ${e.message}")
            false
        }

        val webXmlOk = try { patchWebXml(ctx) } catch (e: Exception) { errors.add("web.xml: ${e.message}"); false }
        val persistenceOk = try { patchPersistenceConfig(ctx) } catch (e: Exception) { errors.add("persistence-configurations.xml: ${e.message}"); false }
        val securityOk = try { patchSecurityProperties(ctx) } catch (e: Exception) { errors.add("security-properties.xml: ${e.message}"); false }
        val configPropsOk = try { patchConfigurationProperties(ctx) } catch (e: Exception) { errors.add("configuration.properties: ${e.message}"); false }

        val keystorePath = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "conf", "sas.keystore")
        val keystoreMissing = !keystorePath.exists()
        if (keystoreMissing) {
            val msg = "tomcat/conf/sas.keystore not found — HTTPS will fail. App. Server container must ship the keystore."
            log.warn(msg)
            NotificationUtil.warn(project, msg)
        }

        return PatchResult(
            serverXmlPatched = serverXmlOk,
            webXmlPatched = webXmlOk,
            persistencePatched = persistenceOk,
            securityPatched = securityOk,
            configPropertiesPatched = configPropsOk,
            serverXmlRestoredFromOrig = restoredFromOrig,
            httpsPortUpdated = httpsPortUpdated,
            keystoreMissing = keystoreMissing,
            errors = errors
        )
    }

    /**
     * Eclipse createTomcatSetup: copy conf/server.xml.orig → conf/server.xml when present
     * so the App. Server baseline (including SSL connector) is restored before ZIDE patches.
     */
    fun restoreServerXmlFromOrig(deploymentFolder: String): Boolean {
        val conf = Path.of(deploymentFolder, "AdventNet", "Sas", "tomcat", "conf")
        val orig = conf.resolve("server.xml.orig")
        val serverXml = conf.resolve("server.xml")
        if (!orig.exists()) return false
        Files.copy(orig, serverXml, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /**
     * Updates port on an existing SSL/HTTPS Connector from ZIDE.HTTPS_PORT.
     * Never injects a new Connector.
     */
    fun updateExistingHttpsPort(ctx: PatchContext): Boolean {
        val port = ctx.httpsPort?.takeIf { it.isNotBlank() } ?: return false
        val serverXml = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "conf", "server.xml")
        if (!serverXml.exists()) return false

        var content = serverXml.readText()
        // Match Connector elements that are SSL/HTTPS (self-closing or with body)
        val connectorRegex = Regex(
            """<Connector\b[^>]*(?:SSLEnabled\s*=\s*"true"|scheme\s*=\s*"https")[^>]*?/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = connectorRegex.find(content) ?: run {
            log.warn("No SSL/HTTPS Connector found in server.xml — App. Server config must provide it")
            return false
        }

        val original = match.value
        val portRegex = Regex("""\bport\s*=\s*"[^"]*"""")
        val updated = if (portRegex.containsMatchIn(original)) {
            portRegex.replace(original, """port="$port"""")
        } else {
            original.replace("<Connector", """<Connector port="$port"""", ignoreCase = true)
        }
        if (updated == original) return false

        content = content.replace(original, updated)
        serverXml.writeText(content)
        return true
    }

    /**
     * Rewrite HTTPS Connector(s) to the working Eclipse zharehub SSL attrs.
     * Preserves the HTTP 8080 connector (never matches redirectPort="8443").
     * Removes all HTTPS/8443 duplicates and inserts exactly one SSL connector.
     */
    fun ensureHttpsConnector(ctx: PatchContext): Boolean {
        val port = ctx.httpsPort?.takeIf { it.isNotBlank() } ?: "8443"
        val serverXml = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "conf", "server.xml")
        if (!serverXml.exists()) return false

        val sslConnector =
            """<Connector SSLEnabled="true" acceptCount="100" clientAuth="false" connectionTimeout="20000" debug="4" disableUploadTimeout="true" enableLookups="false" keystoreFile="conf/sas.keystore" keystorePass="N5${'$'}0IfC:4o:^KJ" keystoreType="JKS" maxSpareThreads="75" maxThreads="150" minSpareThreads="25" parseBodyMethods="POST,PUT" port="$port" relaxedQueryChars="[]|{}" scheme="https" secure="true" sslProtocol="TLS" useBodyEncodingForURI="true"/>"""

        var content = serverXml.readText()
        val allConnectors = Regex(
            """<Connector\b[^>]*?/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(content).map { it.value }.toList()

        // Attribute port= only — not redirectPort=
        val httpsPortAttr = Regex("""(?<![A-Za-z])port\s*=\s*"${Regex.escape(port)}"""", RegexOption.IGNORE_CASE)
        val httpPortAttr = Regex("""(?<![A-Za-z])port\s*=\s*"8080"""", RegexOption.IGNORE_CASE)
        val sslEnabledAttr = Regex("""SSLEnabled\s*=\s*"true"""", RegexOption.IGNORE_CASE)
        val schemeHttpsAttr = Regex("""scheme\s*=\s*"https"""", RegexOption.IGNORE_CASE)

        fun isHttpConnector(connector: String): Boolean = httpPortAttr.containsMatchIn(connector)

        fun isHttpsCandidate(connector: String): Boolean {
            if (isHttpConnector(connector)) return false
            return httpsPortAttr.containsMatchIn(connector) ||
                sslEnabledAttr.containsMatchIn(connector) ||
                schemeHttpsAttr.containsMatchIn(connector)
        }

        val httpsConnectors = allConnectors.filter { isHttpsCandidate(it) }
        for (dup in httpsConnectors) {
            content = content.replace(dup, "")
        }
        // Clean blank lines left by removals
        content = content.replace(Regex("""(\r?\n)[ \t]*(\r?\n){2,}"""), "$1$1")

        val httpConnectorMatch = Regex(
            """<Connector\b[^>]*?/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(content).firstOrNull { isHttpConnector(it.value) }

        content = if (httpConnectorMatch != null) {
            content.replace(
                httpConnectorMatch.value,
                httpConnectorMatch.value + "\n\n    " + sslConnector
            )
        } else {
            content.replace("</Service>", "        $sslConnector\n    </Service>")
        }

        serverXml.writeText(content)
        log.info("Rewrote HTTPS Connector (port=$port); preserved HTTP connector; removed ${httpsConnectors.size} prior HTTPS candidate(s)")
        return true
    }

    fun patchServerXml(ctx: PatchContext): Boolean {
        val serverXml = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "conf", "server.xml")
        if (!serverXml.exists()) return false

        var content = serverXml.readText()
        var modified = false

        if (!content.contains("<Context ")) {
            val hostCloseTag = "</Host>"
            val contextElement = """<Context docBase="${ctx.parentService}" path="/" reloadable="true"/>"""
            content = content.replace(hostCloseTag, "$contextElement$hostCloseTag")
            modified = true
        }

        if (content.contains("""reloadable="false"""")) {
            content = content.replace("""reloadable="false"""", """reloadable="true"""")
            modified = true
        }

        val shutdownPortRegex = Regex("""<Server\s([^>]*?)port="-1"([^>]*?)>""")
        if (shutdownPortRegex.containsMatchIn(content)) {
            content = shutdownPortRegex.replace(content) { match ->
                """<Server ${match.groupValues[1]}port="9285"${match.groupValues[2]}>"""
            }
            modified = true
        }

        if (content.contains("<Host ") && !content.contains("deployOnStartup=")) {
            content = content.replace(Regex("""(<Host\s[^>]*?)(\s*>)"""), "$1 deployOnStartup=\"false\"$2")
            modified = true
        }

        if (modified) serverXml.writeText(content)
        return modified
    }

    private const val JSP_SERVLET_MARKER =
        "<!-- DEFAULT JSP SERVLET AND ITS MAPPING ADDED BY ZIDE TO ENABLE DYNAMIC JSP COMPILATION FOR DEVELOPMENT SETUP -->"

    private const val JSP_SERVLET_BLOCK = """       
       
$JSP_SERVLET_MARKER       
       
<servlet>       
        <servlet-name>jsp</servlet-name>       
        <servlet-class>org.apache.jasper.servlet.JspServlet</servlet-class>       
        <init-param>       
            <param-name>fork</param-name>       
            <param-value>false</param-value>       
        </init-param>
        <init-param>       
            <param-name>xpoweredBy</param-name>       
            <param-value>false</param-value>       
        </init-param>       
        <load-on-startup>3</load-on-startup>       
</servlet>       
       
<servlet-mapping>       
        <servlet-name>jsp</servlet-name>       
        <url-pattern>*.jsp</url-pattern>       
        <url-pattern>*.jspx</url-pattern>       
</servlet-mapping>
       
       
$JSP_SERVLET_MARKER"""

    fun patchWebXml(ctx: PatchContext): Boolean {
        val webXml = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "conf", "web.xml")
        if (!webXml.exists()) return false

        var content = webXml.readText()
        if (content.contains(JSP_SERVLET_MARKER)) return false

        val servletDefsComment = "Built In Servlet Definitions"
        val idx = content.indexOf(servletDefsComment)
        if (idx == -1) return false

        val commentEnd = content.indexOf("-->", idx)
        if (commentEnd == -1) return false
        val insertPos = commentEnd + 3

        val originalComment = content.substring(content.lastIndexOf("<!--", idx), insertPos)
        val modifiedComment = originalComment.replace(
            "Built In Servlet Definitions",
            "Built In Servlet Definitions (modified)"
        )

        content = content.substring(0, content.lastIndexOf("<!--", idx)) +
                modifiedComment + JSP_SERVLET_BLOCK +
                content.substring(insertPos)

        webXml.writeText(content)
        return true
    }

    /**
     * Patches configuration.properties with DB-vendor-specific values.
     * Replicates Eclipse's pgsql_replace.xml / mysql_replace.xml logic.
     */
    fun patchConfigurationProperties(ctx: PatchContext): Boolean {
        val webappDir = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "webapps", ctx.parentService)
        val configProps = webappDir.resolve("WEB-INF").resolve("conf").resolve("configuration.properties")
        if (!configProps.exists()) return false

        var content = configProps.readText()
        var modified = false

        val replacements: Map<String, String> = if (ctx.isPgsql) {
            mapOf(
                "db.drivername" to "org.postgresql.Driver",
                "db.username" to (ctx.dbUser ?: "root"),
                "db.password" to (ctx.dbPass ?: ""),
                "db.url" to "jdbc:postgresql://\$host:\$port/\$dbName?charSet=UNICODE",
                "db.port" to "5432",
                "db.schemaname" to (ctx.schemaName ?: "jbossdb"),
                "db.name" to (ctx.dbName ?: "postgres"),
                "db.vendor.name" to "postgres",
                "sas.dbserver.name" to "POSTGRES"
            )
        } else {
            mapOf(
                "db.drivername" to "org.gjt.mm.mysql.Driver",
                "db.username" to (ctx.dbUser ?: "root"),
                "db.password" to (ctx.dbPass ?: ""),
                "db.url" to "jdbc:mysql://\$host:\$port/\$dbName?",
                "db.port" to "3306",
                "db.schemaname" to (ctx.schemaName ?: "jbossdb"),
                "db.name" to "mysql",
                "db.vendor.name" to "mysql",
                "sas.dbserver.name" to "MYSQL"
            )
        }

        for ((key, value) in replacements) {
            val regex = Regex("""(?m)^(${Regex.escape(key)}=).*$""")
            if (regex.containsMatchIn(content)) {
                val newContent = regex.replace(content, "$1${Regex.escapeReplacement(value)}")
                if (newContent != content) {
                    content = newContent
                    modified = true
                }
            } else if (key == "db.password") {
                content += "\n$key=$value"
                modified = true
            }
        }

        if (modified) configProps.writeText(content)
        return modified
    }

    /**
     * Patches persistence-configurations.xml:
     * - DBName: "postgres" for PGSQL, "mysql" for MYSQL
     * - DSAdapter: "saspg" for PGSQL, "sas" for MYSQL
     * - StartDBServer: "false"
     */
    fun patchPersistenceConfig(ctx: PatchContext): Boolean {
        val webappDir = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "webapps", ctx.parentService)
        val persistenceXml = webappDir.resolve("WEB-INF").resolve("conf").resolve("Persistence")
            .resolve("persistence-configurations.xml")
        if (!persistenceXml.exists()) return false

        var content = persistenceXml.readText()
        var modified = false

        val dbNameValue = if (ctx.isPgsql) "postgres" else "mysql"
        val dbNameRegex = Regex("""(<configuration\s+name="DBName"\s+value=")[^"]*("/)""")
        if (dbNameRegex.containsMatchIn(content)) {
            val newContent = dbNameRegex.replace(content, "$1$dbNameValue$2")
            if (newContent != content) { content = newContent; modified = true }
        }

        val dsAdapterValue = if (ctx.isPgsql) "saspg" else "sas"
        val dsAdapterRegex = Regex("""(<configuration\s+name="DSAdapter"\s+value=")[^"]*("\s*/>)""")
        val firstMatch = dsAdapterRegex.find(content)
        if (firstMatch != null && !firstMatch.value.contains("value=\"$dsAdapterValue\"")) {
            content = content.replaceFirst(dsAdapterRegex, "$1$dsAdapterValue$2")
            modified = true
        }

        val startDbRegex = Regex("""(<configuration\s+name="StartDBServer"\s+value=")[^"]*("/)""")
        if (startDbRegex.containsMatchIn(content)) {
            val newContent = startDbRegex.replace(content, "\$1false$2")
            if (newContent != content) { content = newContent; modified = true }
        }

        if (modified) persistenceXml.writeText(content)
        return modified
    }

    fun patchSecurityProperties(ctx: PatchContext): Boolean {
        val webappDir = Path.of(ctx.deploymentFolder, "AdventNet", "Sas", "tomcat", "webapps", ctx.parentService)
        val securityXml = webappDir.resolve("WEB-INF").resolve("security-properties.xml")
        if (!securityXml.exists()) return false

        var content = securityXml.readText()
        var modified = false

        if (ctx.iamServer != null) {
            val iamRegex = Regex("""(<property\s+name="com\.adventnet\.iam\.internal\.server"\s+value=")[^"]*("/)""")
            if (iamRegex.containsMatchIn(content)) {
                content = iamRegex.replace(content, "$1${Regex.escapeReplacement(ctx.iamServer)}$2")
                modified = true
            }
        }

        if (ctx.iamServiceName != null) {
            val serviceNameRegex = Regex("""(<property\s+name="service\.name"\s+value=")[^"]*("/)""")
            if (serviceNameRegex.containsMatchIn(content)) {
                content = serviceNameRegex.replace(content, "$1${ctx.iamServiceName}$2")
                modified = true
            }
            val securityNameRegex = Regex("""(<security\s[^>]*?name=")[^"]*("[^>]*>)""")
            if (securityNameRegex.containsMatchIn(content)) {
                content = securityNameRegex.replace(content, "$1${ctx.iamServiceName}$2")
                modified = true
            }
        }

        if (ctx.hostName != null && ctx.httpsPort != null && ctx.iamServiceName != null) {
            val logoutUrl = "https://${ctx.hostName}:${ctx.httpsPort}/logout?servicename=${ctx.iamServiceName}"
            val logoutRegex = Regex("""(<property\s+name="logout\.page"\s+value=")[^"]*("/)""")
            if (logoutRegex.containsMatchIn(content)) {
                content = logoutRegex.replace(content, "$1${Regex.escapeReplacement(logoutUrl)}$2")
                modified = true
            }
        }

        if (modified) securityXml.writeText(content)
        return modified
    }

    fun buildPatchContext(
        serviceProps: Map<String, String>,
        zideProps: Map<String, String>
    ): PatchContext? {
        val deploymentFolder = serviceProps["ZIDE.DEPLOYMENT_FOLDER"] ?: return null
        val parentService = serviceProps["ZIDE.PARENT_SERVICE"] ?: return null
        return PatchContext(
            deploymentFolder = deploymentFolder,
            parentService = parentService,
            iamServer = zideProps["ZIDE.IAM_SERVER"],
            iamServiceName = zideProps["ZIDE.IAM_SERVICENAME"],
            hostName = zideProps["ZIDE.HOST_NAME"],
            httpsPort = zideProps["ZIDE.HTTPS_PORT"],
            dbType = zideProps["ZIDE_DB_TYPE"],
            dbName = zideProps["ZIDE_DB_NAME"],
            dbUser = zideProps["ZIDE_DB_USER"],
            dbPass = zideProps["ZIDE_DB_PASS"],
            dbHost = zideProps["ZIDE_DB_HOST"],
            schemaName = zideProps["ZIDE.SCHEMA_NAME"]
        )
    }
}
