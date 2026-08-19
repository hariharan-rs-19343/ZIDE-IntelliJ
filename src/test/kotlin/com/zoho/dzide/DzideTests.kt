package com.zoho.dzide

import com.zoho.dzide.parser.ModuleZidePropsParser
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.zide.ZideConfigParser
import org.junit.Assert.*
import org.junit.Test

class ZideConfigParserTest {

    @Test
    fun `parseServiceXml extracts services with properties`() {
        val xml = """
            <root>
                <service key="ROOT">
                    <property name="ZIDE.DEPLOYMENT_FOLDER" value="/opt/deploy"/>
                    <property name="ZIDE.SERVICE_KEY" value="ZOHOACCOUNTS"/>
                    <property name="ZIDE.HTTP_PORT" value="9090"/>
                </service>
                <service key="SECONDARY">
                    <property name="ZIDE.DEPLOYMENT_FOLDER" value="/opt/deploy2"/>
                </service>
            </root>
        """.trimIndent()

        val services = ZideConfigParser.parseServiceXml(xml)
        assertEquals(2, services.size)
        assertEquals("ROOT", services[0].key)
        assertEquals("/opt/deploy", services[0].properties["ZIDE.DEPLOYMENT_FOLDER"])
        assertEquals("ZOHOACCOUNTS", services[0].properties["ZIDE.SERVICE_KEY"])
        assertEquals("SECONDARY", services[1].key)
    }

    @Test
    fun `extractTomcatPath builds correct path`() {
        val xml = """
            <root>
                <service key="ROOT">
                    <property name="ZIDE.DEPLOYMENT_FOLDER" value="/opt/deploy"/>
                </service>
            </root>
        """.trimIndent()
        val services = ZideConfigParser.parseServiceXml(xml)
        val path = ZideConfigParser.extractTomcatPath(services[0])
        assertTrue(path!!.endsWith("AdventNet/Sas/tomcat") || path.endsWith("AdventNet\\Sas\\tomcat"))
    }

    @Test
    fun `extractHttpPort returns parsed port or default`() {
        val result = ZideConfigParser.parsePropertiesXml("""
            <root>
                <service key="ROOT">
                    <property name="ZIDE.HTTP_PORT" value="9090"/>
                </service>
            </root>
        """.trimIndent())
        assertNotNull(result)
        assertEquals(9090, ZideConfigParser.extractHttpPort(result!!))
    }

    @Test
    fun `computeCombinedHash is deterministic`() {
        val hash1 = ZideConfigParser.computeCombinedHash("content1", "content2")
        val hash2 = ZideConfigParser.computeCombinedHash("content1", "content2")
        assertEquals(hash1, hash2)
        assertNotEquals(hash1, ZideConfigParser.computeCombinedHash("content1", "content3"))
    }
}

class ModuleZidePropsParserTest {

    @Test
    fun `parseJavaProperties handles standard format`() {
        val content = """
            # comment
            launch.vmarguments=-Xms512m -Xmx2048m
            hooks.resourcemodify.all.calltasks=web:deploy-web,conf:deploy-conf
            empty.key=
        """.trimIndent()

        val props = ModuleZidePropsParser.parseJavaProperties(content)
        assertEquals("-Xms512m -Xmx2048m", props["launch.vmarguments"])
        assertEquals("web:deploy-web,conf:deploy-conf", props["hooks.resourcemodify.all.calltasks"])
        assertEquals("", props["empty.key"])
        assertNull(props["# comment"])
    }

    @Test
    fun `parseHookTaskMappings parses comma-separated entries`() {
        val raw = "web:deploy-web,conf:deploy-conf"
        val mappings = ModuleZidePropsParser.parseHookTaskMappings(raw, "myproject")
        assertEquals(2, mappings.size)
        assertEquals("web", mappings[0].folder)
        assertEquals("deploy-web", mappings[0].antTarget)
        assertEquals("conf", mappings[1].folder)
        assertEquals("deploy-conf", mappings[1].antTarget)
    }

    @Test
    fun `parseHookTaskMappings strips project prefix`() {
        val raw = "myproject/web:deploy-web"
        val mappings = ModuleZidePropsParser.parseHookTaskMappings(raw, "myproject")
        assertEquals(1, mappings.size)
        assertEquals("web", mappings[0].folder)
    }

    @Test
    fun `parseAutoCopyMappings parses entries correctly`() {
        val raw = "static:webapps/{PROJECT_NAME}/static"
        val mappings = ModuleZidePropsParser.parseAutoCopyMappings(raw, "myapp")
        assertEquals(1, mappings.size)
        assertEquals("static", mappings[0].sourcePath)
        assertEquals("webapps/{PROJECT_NAME}/static", mappings[0].destinationPathTemplate)
    }

    @Test
    fun `null or blank input returns empty list`() {
        assertTrue(ModuleZidePropsParser.parseHookTaskMappings(null, "p").isEmpty())
        assertTrue(ModuleZidePropsParser.parseHookTaskMappings("  ", "p").isEmpty())
        assertTrue(ModuleZidePropsParser.parseAutoCopyMappings(null, "p").isEmpty())
    }
}

class PathResolverTest {

    @Test
    fun `normalizePathSlashes converts backslashes`() {
        assertEquals("a/b/c", PathResolver.normalizePathSlashes("a\\b\\c"))
    }

    @Test
    fun `stripProjectPrefix removes project name`() {
        assertEquals("web/index.html", PathResolver.stripProjectPrefix("myapp/web/index.html", "myapp"))
        assertEquals("web/index.html", PathResolver.stripProjectPrefix("web/index.html", "other"))
    }

    @Test
    fun `applyProjectNamePlaceholder replaces token`() {
        assertEquals("webapps/myapp/static",
            PathResolver.applyProjectNamePlaceholder("webapps/{PROJECT_NAME}/static", "myapp"))
    }

    @Test
    fun `resolveModuleRecipeDir uses deployment moduleDir deployType`() {
        val dir = PathResolver.resolveModuleRecipeDir("/ws/zide", "zharehub_cloud", "M19")
        assertTrue(dir.replace('\\', '/').endsWith("zide/deployment/zharehub_cloud/M19"))
    }

    @Test
    fun `resolveTomcatHome defaults to AdventNet Sas tomcat`() {
        val home = PathResolver.resolveTomcatHome("/ws/deployment/zharehub")
        assertTrue(home.replace('\\', '/').endsWith("deployment/zharehub/AdventNet/Sas/tomcat"))
    }

    @Test
    fun `resolveTomcatHome uses deploy folder basepath`() {
        val home = PathResolver.resolveTomcatHome("/ws/deployment/app", "AdventNet/Sas/tomcat")
        assertTrue(home.replace('\\', '/').endsWith("deployment/app/AdventNet/Sas/tomcat"))
    }
}
