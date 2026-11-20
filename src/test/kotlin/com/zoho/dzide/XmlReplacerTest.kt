package com.zoho.dzide

import com.zoho.dzide.config.replacer.InstallXmlParser
import com.zoho.dzide.config.replacer.XmlReplacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XmlReplacerTest {

    private val serverXmlOrig = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- ${'$'}Id${'$'} --><Server port="-1">
          <Service name="Catalina">
            <Connector port="8080" connectionTimeout="20000" redirectPort="8443"/>
            <Connector port="8443" secure="true" scheme="https" connectionTimeout="20000"/>
            <Engine name="Catalina" defaultHost="localhost">
              <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false">
                <Valve className="org.apache.catalina.valves.ErrorReportValve" showReport="false"/>
                <Valve className="org.apache.catalina.valves.AccessLogValve" prefix="access_log"/>
              </Host>
            </Engine>
          </Service>
        </Server>
    """.trimIndent()

    // Same rules as zide/deployment/zharehub_cloud/M19/install.xml for conf/server.xml.orig
    private val installXml = """
        <?xml version="1.0" ?>
        <configurations>
          <configuration branch="default">
            <files>
              <file path="conf/server.xml.orig" type="xml">
                <property xpath="/Server/Service/Connector[1]" regex="(port=&quot;).*?&quot;">
                  <value><![CDATA[\1{ZIDE.HTTP_PORT}"]]></value>
                </property>
                <property xpath="/Server/Service/Connector[1]" regex="(redirectPort=&quot;).*?&quot;">
                  <value><![CDATA[\1{ZIDE.HTTPS_PORT}"]]></value>
                </property>
                <property xpath="//Connector[@scheme='https']" regex=".+">
                  <value><![CDATA[<Connector port="{ZIDE.HTTPS_PORT}" SSLEnabled="true" scheme="https" secure="true" keystoreFile="conf/sas.keystore" />]]></value>
                </property>
                <property xpath="//Host" newAttribute="true">
                  <value><![CDATA[deployOnStartup=false]]></value>
                </property>
              </file>
            </files>
          </configuration>
        </configurations>
    """.trimIndent()

    private fun apply(target: File, props: Map<String, String>) {
        val install = Files.createTempFile("install", ".xml").toFile().apply { writeText(installXml) }
        val rules = InstallXmlParser(install, props).parse("default")
        XmlReplacer.replace(rules.getValue("conf/server.xml.orig:xml"), target)
    }

    private fun newTarget(): File =
        Files.createTempFile("server", ".xml.orig").toFile().apply { writeText(serverXmlOrig) }

    private val ports = mapOf("ZIDE.HTTP_PORT" to "9090", "ZIDE.HTTPS_PORT" to "9443")

    @Test
    fun `regex rule rewrites attributes of the serialized element`() {
        val target = newTarget()
        apply(target, ports)
        val out = target.readText()
        assertTrue(out, out.contains("""port="9090""""))
        assertTrue(out, out.contains("""redirectPort="9443""""))
    }

    @Test
    fun `regex dot-plus replaces the whole https connector`() {
        val target = newTarget()
        apply(target, ports)
        val out = target.readText()
        assertTrue(out, Regex("""<Connector[^>]*SSLEnabled="true"[^>]*port="9443"""").containsMatchIn(out) ||
            Regex("""<Connector[^>]*port="9443"[^>]*SSLEnabled="true"""").containsMatchIn(out))
        assertTrue(out, out.contains("conf/sas.keystore"))
        assertEquals(out, 2, Regex("<Connector").findAll(out).count())
    }

    @Test
    fun `newAttribute adds attribute and keeps Host children`() {
        val target = newTarget()
        apply(target, ports)
        val out = target.readText()
        assertTrue(out, Regex("""<Host[^>]*deployOnStartup="false"""").containsMatchIn(out))
        assertFalse(out, out.contains(">deployOnStartup=false<"))
        assertTrue(out, out.contains("ErrorReportValve"))
        assertTrue(out, out.contains("AccessLogValve"))
    }

    @Test
    fun `repeated runs are idempotent`() {
        val target = newTarget()
        apply(target, ports)
        val first = target.readText()
        apply(target, ports)
        apply(target, ports)
        assertEquals(first, target.readText())
    }

    @Test
    fun `unresolved ZIDE placeholder leaves element untouched`() {
        val target = newTarget()
        apply(target, emptyMap())
        val out = target.readText()
        assertTrue(out, Regex("""<Connector[^>]*\sport="8080"""").containsMatchIn(out))
        assertFalse(out, out.contains("SSLEnabled"))
        // newAttribute has no placeholder, so it still applies.
        assertTrue(out, Regex("""<Host[^>]*deployOnStartup="false"""").containsMatchIn(out))
    }
}
