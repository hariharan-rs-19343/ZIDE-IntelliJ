package com.zoho.dzide

import com.zoho.dzide.settings.CredentialResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CredentialResolverTest {

    private fun tempHome(): File = Files.createTempDirectory("dzide-home").toFile().also { it.deleteOnExit() }

    @Test
    fun `parseZideConf reads cmtools token`() {
        assertEquals("abc123", CredentialResolver.parseZideConf("zide.cmtools.authtoken=abc123\n"))
        assertEquals("abc123", CredentialResolver.parseZideConf("# c\nother=1\nzide.cmtools.authtoken = abc123 \n"))
    }

    @Test
    fun `parseZideConf returns null when key missing or blank`() {
        assertNull(CredentialResolver.parseZideConf("other=1\n"))
        assertNull(CredentialResolver.parseZideConf("zide.cmtools.authtoken=\n"))
    }

    @Test
    fun `parseWgetrc reads user and password`() {
        assertEquals("u" to "p", CredentialResolver.parseWgetrc("user=u\npassword=p\n"))
        assertEquals("u" to "p w", CredentialResolver.parseWgetrc("# comment\n user = u \npassword = p w\n"))
    }

    @Test
    fun `parseWgetrc prefers http variants`() {
        assertEquals(
            "hu" to "hp",
            CredentialResolver.parseWgetrc("user=u\npassword=p\nhttp_user=hu\nhttp-password=hp\n")
        )
    }

    @Test
    fun `parseWgetrc requires both values`() {
        assertNull(CredentialResolver.parseWgetrc("user=u\n"))
        assertNull(CredentialResolver.parseWgetrc("#user=u\n#password=p\n"))
    }

    @Test
    fun `buildWgetrc replaces credential lines and keeps others`() {
        val out = CredentialResolver.buildWgetrc("tries=3\nuser=old\nhttp_password=oldp\n", "new", "np")
        assertEquals("tries=3\nuser=new\npassword=np\n", out)
        assertEquals("user=new\npassword=np\n", CredentialResolver.buildWgetrc("", "new", "np"))
    }

    @Test
    fun `parseNetrc matches host on single and multi line entries`() {
        val text = """
            machine other.com login o password op
            machine build.zohocorp.com
              login bu
              password bp
            default login du password dp
        """.trimIndent()
        assertEquals("bu" to "bp", CredentialResolver.parseNetrc(text, "build.zohocorp.com"))
        assertEquals("o" to "op", CredentialResolver.parseNetrc(text, "OTHER.com"))
        assertEquals("du" to "dp", CredentialResolver.parseNetrc(text, "unknown.com"))
    }

    @Test
    fun `parseNetrc returns null for unmatched host without default`() {
        assertNull(CredentialResolver.parseNetrc("machine a.com login x password y", "b.com"))
    }

    @Test
    fun `parseHgrcAuth picks longest matching prefix`() {
        val text = """
            [ui]
            username = someone
            [auth]
            all.prefix = *
            all.username = any
            all.password = anyp
            cm.prefix = https://cmsuite.csez.zohocorpin.com/eclipse
            cm.username = cmu
            cm.password = cmp
            other.prefix = other.host
            other.username = ou
            other.password = op
        """.trimIndent()
        assertEquals("cmu" to "cmp", CredentialResolver.parseHgrcAuth(text, "https://cmsuite.csez.zohocorpin.com/eclipse/zide"))
        assertEquals("any" to "anyp", CredentialResolver.parseHgrcAuth(text, "https://elsewhere/repo"))
    }

    @Test
    fun `parseHgrcAuth ignores entries outside auth section`() {
        assertNull(CredentialResolver.parseHgrcAuth("[paths]\nx.prefix=*\nx.username=u\nx.password=p\n", "https://h/r"))
    }

    @Test
    fun `parseGitCredentialOutput reads username and password`() {
        assertEquals(
            "gu" to "gp",
            CredentialResolver.parseGitCredentialOutput("protocol=https\nhost=h\nusername=gu\npassword=gp\n")
        )
        assertNull(CredentialResolver.parseGitCredentialOutput("protocol=https\nhost=h\n"))
    }

    @Test
    fun `cmtool token from settings wins over zide conf`() {
        val home = tempHome()
        File(home, ".zide").mkdirs()
        File(home, ".zide/zide.conf").writeText("zide.cmtools.authtoken=global\n")

        val fromSettings = CredentialResolver.resolveCmToolToken("mine", home)!!
        assertEquals("mine", fromSettings.secret)
        assertEquals(CredentialResolver.SOURCE_SETTINGS, fromSettings.source)

        val fromGlobal = CredentialResolver.resolveCmToolToken(" ", home)!!
        assertEquals("global", fromGlobal.secret)
        assertEquals(CredentialResolver.SOURCE_ZIDE_CONF, fromGlobal.source)

        assertNull(CredentialResolver.resolveCmToolToken("", tempHome()))
    }

    @Test
    fun `wget settings win, then wgetrc, then netrc`() {
        val home = tempHome()
        File(home, ".netrc").writeText("machine build.zohocorp.com login nu password np\n")
        assertEquals(CredentialResolver.SOURCE_NETRC, CredentialResolver.resolveWget("", "", home)!!.source)

        File(home, ".wgetrc").writeText("user=wu\npassword=wp\n")
        val global = CredentialResolver.resolveWget("", "", home)!!
        assertEquals("wu", global.username)
        assertEquals(CredentialResolver.SOURCE_WGETRC, global.source)

        val settings = CredentialResolver.resolveWget("su", "sp", home)!!
        assertEquals("su" to "sp", settings.username to settings.secret)
        assertTrue(settings.fromSettings)

        // Username without password is not a usable Settings credential.
        assertEquals(CredentialResolver.SOURCE_WGETRC, CredentialResolver.resolveWget("su", "", home)!!.source)
        assertNull(CredentialResolver.resolveWget("", "", tempHome()))
    }

    @Test
    fun `zoho repo global reads hgrc then netrc`() {
        val home = tempHome()
        val url = "https://cmsuite.csez.zohocorpin.com/eclipse/zide"
        File(home, ".netrc").writeText("machine cmsuite.csez.zohocorpin.com login nu password np\n")
        assertEquals(CredentialResolver.SOURCE_NETRC, CredentialResolver.resolveZohoRepoGlobal(url, home)!!.source)

        File(home, ".hgrc").writeText("[auth]\ncm.prefix=cmsuite.csez.zohocorpin.com\ncm.username=hu\ncm.password=hp\n")
        val hg = CredentialResolver.resolveZohoRepoGlobal(url, home)!!
        assertEquals("hu", hg.username)
        assertEquals(CredentialResolver.SOURCE_HGRC, hg.source)
    }

    @Test
    fun `resolved credential toString hides secret`() {
        val cred = CredentialResolver.ResolvedCredential("u", "s3cret", CredentialResolver.SOURCE_SETTINGS)
        assertFalse(cred.toString().contains("s3cret"))
    }

    @Test
    fun `looksLikeAuthFailure detects git and wget auth errors`() {
        assertTrue(CredentialResolver.looksLikeAuthFailure("fatal: Authentication failed for 'https://h/r.git/'"))
        assertTrue(CredentialResolver.looksLikeAuthFailure("Username/Password Authentication Failed."))
        assertTrue(CredentialResolver.looksLikeAuthFailure("HTTP request sent, awaiting response... 401 Unauthorized"))
        assertFalse(CredentialResolver.looksLikeAuthFailure("fatal: Remote branch foo not found"))
    }
}
