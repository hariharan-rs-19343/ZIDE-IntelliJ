package com.zoho.dzide

import com.intellij.util.net.ProxyConfiguration
import com.zoho.dzide.util.IdeProxyAction
import com.zoho.dzide.util.SavedIdeProxy
import com.zoho.dzide.util.ZeroProxy
import com.zoho.dzide.util.ZeroProxyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ZeroProxyTest {

    private val mac = "Mac OS X"
    private val noEnv = emptyMap<String, String>()

    private fun home(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("zero-proxy-home").toFile()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    @Test
    fun `zshrc with ZERO_HELPER_STATUS=ON turns the agent on`() {
        val home = home(".zshrc" to "export PATH=/usr/bin\nexport ZERO_HELPER_STATUS=ON\n")
        assertTrue(ZeroProxy.isAgentOn(home, mac, noEnv))
    }

    @Test
    fun `zshrc with OFF or a commented line keeps the agent off`() {
        assertFalse(ZeroProxy.isAgentOn(home(".zshrc" to "export ZERO_HELPER_STATUS=OFF\n"), mac, noEnv))
        assertFalse(ZeroProxy.isAgentOn(home(".zshrc" to "# export ZERO_HELPER_STATUS=ON\n"), mac, noEnv))
    }

    @Test
    fun `last assignment wins and quotes are accepted`() {
        assertFalse(ZeroProxy.parseHelperStatus("ZERO_HELPER_STATUS=ON\nZERO_HELPER_STATUS=OFF"))
        assertTrue(ZeroProxy.parseHelperStatus("export ZERO_HELPER_STATUS=\"ON\""))
    }

    @Test
    fun `missing profile keeps the agent off`() {
        assertFalse(ZeroProxy.isAgentOn(home(), mac, noEnv))
    }

    @Test
    fun `mac reads only the first existing profile like Eclipse`() {
        val home = home(".zshrc" to "", ".bash_profile" to "ZERO_HELPER_STATUS=ON")
        assertFalse(ZeroProxy.isAgentOn(home, mac, noEnv))
        assertTrue(ZeroProxy.isAgentOn(home(".bash_profile" to "ZERO_HELPER_STATUS=ON"), mac, noEnv))
    }

    @Test
    fun `windows uses the environment variable only`() {
        val home = home(".bashrc" to "ZERO_HELPER_STATUS=ON")
        assertFalse(ZeroProxy.isAgentOn(home, "Windows 11", noEnv))
        assertTrue(ZeroProxy.isAgentOn(home, "Windows 11", mapOf("ZERO_HELPER_STATUS" to "on")))
    }

    @Test
    fun `launch args gain proxy flags once`() {
        val proxy = ZeroProxy.vmArgsFor(ZeroProxy.DEFAULT_ENDPOINT)
        val once = ZeroProxy.mergeLaunchArgs("-Xmx2g", proxy)
        assertEquals("-Xmx2g $proxy", once)
        assertEquals(once, ZeroProxy.mergeLaunchArgs(once, proxy))
        assertEquals(proxy, ZeroProxy.mergeLaunchArgs(null, proxy))
        assertEquals("-Xmx2g", ZeroProxy.mergeLaunchArgs("-Xmx2g", null))
        assertNull(ZeroProxy.mergeLaunchArgs("  ", null))
    }

    @Test
    fun `launch proxy vmargs come from module then shared Zide properties`() {
        val repo = Files.createTempDirectory("zide-repo").toFile()
        val shared = File(repo, "deployment/zide/Zide.properties").apply {
            parentFile.mkdirs()
            writeText("launch.proxy.vmargs=\"-Dhttp.proxyHost=10.0.0.1 -Dhttp.proxyPort=8080\"\n")
        }
        val module = File(repo, "deployment/mod/M19/Zide.properties").apply {
            parentFile.mkdirs()
            writeText("launch.vmarguments=-Xmx1g\n")
        }
        val args = ZeroProxy.readProxyVmArgs(module.path, repo.path)
        assertEquals("-Dhttp.proxyHost=10.0.0.1 -Dhttp.proxyPort=8080", args)
        assertEquals(ZeroProxy.Endpoint("10.0.0.1", 8080), ZeroProxy.endpointFromVmArgs(args))

        module.writeText("launch.proxy.vmargs=-Dhttp.proxyHost=127.0.0.2 -Dhttp.proxyPort=3129\n")
        assertEquals(ZeroProxy.Endpoint("127.0.0.2", 3129), ZeroProxy.endpointFromVmArgs(ZeroProxy.readProxyVmArgs(module.path, repo.path)))

        shared.delete()
        module.delete()
        assertNull(ZeroProxy.readProxyVmArgs(module.path, repo.path))
        assertEquals(ZeroProxy.DEFAULT_ENDPOINT, ZeroProxy.endpointFromVmArgs(null))
    }

    @Test
    fun `child env has the proxy only while the agent is on`() {
        val env = ZeroProxy.childEnv(ZeroProxy.DEFAULT_ENDPOINT, active = true)
        assertEquals("http://127.0.0.1:3128", env["http_proxy"])
        assertEquals("http://127.0.0.1:3128", env["https_proxy"])
        assertEquals("localhost,127.0.0.1", env["no_proxy"])
        assertTrue(ZeroProxy.childEnv(ZeroProxy.DEFAULT_ENDPOINT, active = false).isEmpty())
    }

    @Test
    fun `ide action follows setting, agent, unalter, and applied state`() {
        assertEquals(IdeProxyAction.APPLY, ZeroProxyManager.decideIdeAction(true, true, false, false))
        assertEquals(IdeProxyAction.RESTORE, ZeroProxyManager.decideIdeAction(true, false, false, true))
        assertEquals(IdeProxyAction.RESTORE, ZeroProxyManager.decideIdeAction(false, true, false, true))
        assertEquals(IdeProxyAction.NONE, ZeroProxyManager.decideIdeAction(true, false, false, false))
        assertEquals(IdeProxyAction.NONE, ZeroProxyManager.decideIdeAction(true, true, true, false))
    }

    @Test
    fun `saved previous ide proxy is what gets restored`() {
        val previous = ProxyConfiguration.proxy(
            ProxyConfiguration.ProxyProtocol.SOCKS, "corp-proxy", 1080, "*.local|intra"
        )
        val encoded = ZeroProxyManager.snapshot(previous).encode()
        val restored = ZeroProxyManager.toConfiguration(SavedIdeProxy.decode(encoded)!!)
                as ProxyConfiguration.StaticProxyConfiguration
        assertEquals(ProxyConfiguration.ProxyProtocol.SOCKS, restored.protocol)
        assertEquals("corp-proxy", restored.host)
        assertEquals(1080, restored.port)
        assertEquals("*.local|intra", restored.exceptions)

        val direct = SavedIdeProxy.decode(ZeroProxyManager.snapshot(ProxyConfiguration.direct).encode())!!
        assertTrue(ZeroProxyManager.toConfiguration(direct) is ProxyConfiguration.DirectProxy)
        assertNull(SavedIdeProxy.decode(""))
    }
}
