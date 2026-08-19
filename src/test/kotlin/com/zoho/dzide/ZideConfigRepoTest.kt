package com.zoho.dzide

import com.zoho.dzide.config.replacer.ConfigReplacerRunner
import com.zoho.dzide.parser.PathResolver
import com.zoho.dzide.zide.ZideConfigRepoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ZideConfigRepoServiceTest {

    @Test
    fun `parseModuleDirFromCatalog matches ZhareHub name`() {
        val xml = """
            <zide>
              <services>
                <service key="ZHAREHUB_CLOUD" isdeployable="true" name="ZhareHub" moduledir="zharehub_cloud">
                </service>
              </services>
            </zide>
        """.trimIndent()
        assertEquals("zharehub_cloud", ZideConfigRepoService.parseModuleDirFromCatalog(xml, "ZhareHub"))
        assertEquals("zharehub_cloud", ZideConfigRepoService.parseModuleDirFromCatalog(xml, "zharehub"))
        assertEquals("zharehub_cloud", ZideConfigRepoService.parseModuleDirFromCatalog(xml, "zharehub", "ZHAREHUB_CLOUD"))
    }

    @Test
    fun `parseModuleDirFromCatalog returns null when unmatched`() {
        val xml = """<service key="OTHER" name="Other" moduledir="other"></service>"""
        assertNull(ZideConfigRepoService.parseModuleDirFromCatalog(xml, "zharehub"))
    }

    @Test
    fun `parseCredentialBody splits user and password`() {
        assertEquals("ro-user" to "s3cret", ZideConfigRepoService.parseCredentialBody("ro-user:s3cret"))
        assertEquals("user" to "a:b:c", ZideConfigRepoService.parseCredentialBody("user:a:b:c"))
        assertNull(ZideConfigRepoService.parseCredentialBody(""))
        assertNull(ZideConfigRepoService.parseCredentialBody(":nopassuser"))
    }

    @Test
    fun `embedCredentials inserts userinfo`() {
        val url = ZideConfigRepoService.embedCredentials(
            "https://cmsuite.csez.zohocorpin.com/eclipse/zide",
            "anandkumar.k+ro",
            "p@ss"
        )
        assertTrue(url.startsWith("https://anandkumar.k%2Bro:p%40ss@cmsuite.csez.zohocorpin.com/eclipse/zide"))
    }

    @Test
    fun `stripUrlUserInfo removes credentials from hgrc path`() {
        val raw = "https://user:pass@cmsuite.csez.zohocorpin.com/eclipse/zide"
        assertEquals(
            "https://cmsuite.csez.zohocorpin.com/eclipse/zide",
            ZideConfigRepoService.stripUrlUserInfo(raw)
        )
    }

    @Test
    fun `stripUserInfoFromHgrc rewrites default path`() {
        val root = Files.createTempDirectory("zide-hgrc").toFile()
        try {
            val hgDir = File(root, ".hg")
            hgDir.mkdirs()
            val hgrc = File(hgDir, "hgrc")
            hgrc.writeText(
                """
                [paths]
                default = https://user:pass@cmsuite.csez.zohocorpin.com/eclipse/zide
                """.trimIndent() + "\n"
            )
            ZideConfigRepoService.stripUserInfoFromHgrc(root)
            val content = hgrc.readText()
            assertFalse(content.contains("user:pass@"))
            assertTrue(content.contains("https://cmsuite.csez.zohocorpin.com/eclipse/zide"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `redactUrlUserInfo strips credentials from failure blob`() {
        val blob = "hg clone failed (exit 255): abort: error: " +
            "https://user:pass@cmsuite.csez.zohocorpin.com/eclipse/zide " +
            "http://ro-user:s3cret@cmsuite.csez.zohocorpin.com/path"
        val redacted = ZideConfigRepoService.redactUrlUserInfo(blob)
        assertFalse(redacted.contains("user:pass@"))
        assertFalse(redacted.contains("ro-user:s3cret@"))
        assertTrue(redacted.contains("https://cmsuite.csez.zohocorpin.com/eclipse/zide"))
        assertTrue(redacted.contains("http://cmsuite.csez.zohocorpin.com/path"))
        assertEquals("", ZideConfigRepoService.redactUrlUserInfo(null))
        assertEquals("", ZideConfigRepoService.redactUrlUserInfo(""))
    }
}

class ConfigReplacerRunnerTest {

    @Test
    fun `resolveInstallXml prefers workspace zide deployment M19`() {
        val workspace = Files.createTempDirectory("zide-ws")
        try {
            val project = workspace.resolve("zharehub")
            project.createDirectories()
            val recipe = workspace.resolve("zide/deployment/zharehub_cloud/M19")
            recipe.createDirectories()
            val installXml = recipe.resolve("install.xml")
            installXml.writeText("<configurations/>")

            val found = ConfigReplacerRunner.resolveInstallXml(
                project.toString(),
                mapOf(
                    "ZIDE.REPOSITORY_MODULE_DIR" to "zharehub_cloud",
                    "ZIDE.DEPLOY_TYPE" to "M19"
                )
            )
            assertNotNull(found)
            assertEquals(installXml.toFile().canonicalPath, found!!.canonicalPath)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveReplaceRoot appends AdventNet Sas tomcat`() {
        val workspace = Files.createTempDirectory("zide-ws")
        try {
            val project = workspace.resolve("zharehub")
            project.createDirectories()
            val recipe = workspace.resolve("zide/deployment/zharehub_cloud/M19")
            recipe.createDirectories()
            recipe.resolve("Zide.properties").writeText("deploy.folder.basepath=AdventNet/Sas/tomcat\n")

            val root = ConfigReplacerRunner.resolveReplaceRoot(
                project.toString(),
                mapOf(
                    "ZIDE.REPOSITORY_MODULE_DIR" to "zharehub_cloud",
                    "ZIDE.DEPLOY_TYPE" to "M19"
                ),
                workspace.resolve("deployment/zharehub").toString()
            )
            assertTrue(root.replace('\\', '/').endsWith("deployment/zharehub/AdventNet/Sas/tomcat"))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run applies install xml against tomcat home not deployment root`() {
        val workspace = Files.createTempDirectory("zide-ws")
        try {
            val project = workspace.resolve("zharehub")
            project.createDirectories()
            val recipe = workspace.resolve("zide/deployment/zharehub_cloud/M19")
            recipe.createDirectories()
            recipe.resolve("Zide.properties").writeText("deploy.folder.basepath=AdventNet/Sas/tomcat\n")
            recipe.resolve("install.xml").writeText(
                """
                <?xml version="1.0"?>
                <configurations>
                  <configuration branch="default">
                    <files>
                      <file path="webapps/{PROJECT_NAME}/WEB-INF/conf/configuration.properties" type="text">
                        <property regex="production=.*">
                          <value>production=false</value>
                        </property>
                      </file>
                    </files>
                  </configuration>
                </configurations>
                """.trimIndent()
            )

            val deployment = workspace.resolve("deployment/zharehub")
            val conf = deployment.resolve("AdventNet/Sas/tomcat/webapps/zharehub/WEB-INF/conf")
            conf.createDirectories()
            val target = conf.resolve("configuration.properties")
            target.writeText("production=true\n")
            // Wrong root would look here — leave it unchanged if replace root is correct.
            val wrong = deployment.resolve("webapps/zharehub/WEB-INF/conf")
            wrong.createDirectories()
            wrong.resolve("configuration.properties").writeText("production=true\n")

            val result = ConfigReplacerRunner.run(
                projectPath = project.toString(),
                deploymentFolder = deployment.toString(),
                serviceProps = mapOf(
                    "ZIDE.REPOSITORY_MODULE_DIR" to "zharehub_cloud",
                    "ZIDE.DEPLOY_TYPE" to "M19",
                    "ZIDE.PARENT_SERVICE" to "zharehub",
                    "ZIDE.SERVICE_KEY" to "ZHAREHUB_CLOUD"
                ),
                zideProps = emptyMap(),
                branch = "default"
            )
            assertTrue(result.applied)
            assertEquals("production=false\n", target.toFile().readText())
            assertEquals("production=true\n", wrong.resolve("configuration.properties").toFile().readText())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveZideConfigRepoFromProject finds sibling zide`() {
        val workspace = Files.createTempDirectory("zide-ws")
        try {
            workspace.resolve("zide").createDirectories()
            val project = workspace.resolve("zharehub")
            project.createDirectories()
            val found = PathResolver.resolveZideConfigRepoFromProject(project.toString())
            assertNotNull(found)
            assertEquals(workspace.resolve("zide").toFile().canonicalPath, Path.of(found!!).toFile().canonicalPath)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
