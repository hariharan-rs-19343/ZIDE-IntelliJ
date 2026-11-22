package com.zoho.dzide

import com.zoho.dzide.deploysync.BuildOutputLocator
import com.zoho.dzide.util.ProtocConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class BuildOutputLocatorTest {

    private fun tempBuildDir(): Path = Files.createTempDirectory("dzide-build").also { it.toFile().deleteOnExit() }

    private fun zip(path: Path, modified: Long): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "zip")
        path.toFile().setLastModified(modified)
        return path
    }

    @Test
    fun `zharehub style properties resolve expected zip`() {
        val buildDir = tempBuildDir()
        Files.writeString(
            buildDir.resolve("ant.properties"),
            "product_name=zharehub_cloud\nbuild_dir=\${basedir}/ZHAREHUB_CLOUD\noutput_dir=\${build_dir}/output\n"
        )
        assertEquals(
            buildDir.resolve("ZHAREHUB_CLOUD/output/zharehub_cloud.zip"),
            BuildOutputLocator.expectedZip(buildDir)
        )
    }

    @Test
    fun `quoted product name and nested reference resolve`() {
        val buildDir = tempBuildDir()
        val props = BuildOutputLocator.parseAntProperties(
            "product_name=\"ZHAREHUB_CLOUD_TP\"\nbuild_dir=\${basedir}/\${product_name}\noutput_dir=\${build_dir}/output\n",
            buildDir
        )
        assertEquals("ZHAREHUB_CLOUD_TP", props["product_name"])
        assertEquals("$buildDir/ZHAREHUB_CLOUD_TP/output", props["output_dir"])
    }

    @Test
    fun `unresolved reference is left as is`() {
        val props = BuildOutputLocator.parseAntProperties("a=\${missing}/x\n", Path.of("/b"))
        assertEquals("\${missing}/x", props["a"])
    }

    @Test
    fun `find skips stale expected zip and picks newest fresh zip in output dir`() {
        val buildDir = tempBuildDir()
        Files.writeString(buildDir.resolve("ant.properties"), "product_name=p\noutput_dir=\${basedir}/out\n")
        val start = System.currentTimeMillis() - 60_000
        zip(buildDir.resolve("out/p.zip"), start - 10_000)
        zip(buildDir.resolve("out/older.zip"), start + 1_000)
        val newest = zip(buildDir.resolve("out/newer.zip"), start + 5_000)

        assertEquals(newest to BuildOutputLocator.SOURCE_ANT_PROPERTIES, BuildOutputLocator.find(buildDir, null, null, start))
    }

    @Test
    fun `find prefers expected zip when fresh`() {
        val buildDir = tempBuildDir()
        Files.writeString(buildDir.resolve("ant.properties"), "product_name=p\noutput_dir=\${basedir}/out\n")
        val start = System.currentTimeMillis() - 60_000
        val expected = zip(buildDir.resolve("out/p.zip"), start + 1_000)
        zip(buildDir.resolve("out/other.zip"), start + 5_000)

        assertEquals(expected, BuildOutputLocator.find(buildDir, null, null, start)?.first)
    }

    @Test
    fun `find falls back to local download url then saved path`() {
        val buildDir = tempBuildDir()
        val start = System.currentTimeMillis() - 60_000
        val elsewhere = tempBuildDir()
        val fromUrl = zip(elsewhere.resolve("dist/product.zip"), start + 1_000)
        val saved = zip(elsewhere.resolve("saved/s.zip"), start + 2_000)

        assertEquals(
            fromUrl to BuildOutputLocator.SOURCE_DOWNLOAD_URL,
            BuildOutputLocator.find(buildDir, fromUrl.toString(), saved.toString(), start)
        )
        // Folder in ZIDE.DOWNLOAD_URL → newest fresh zip in it.
        assertEquals(fromUrl, BuildOutputLocator.find(buildDir, fromUrl.parent.toString(), null, start)?.first)
        assertEquals(
            saved to BuildOutputLocator.SOURCE_SAVED,
            BuildOutputLocator.find(buildDir, "https://build.zohocorp.com/x/product.zip", saved.toString(), start)
        )
    }

    @Test
    fun `find returns null when every candidate is stale`() {
        val buildDir = tempBuildDir()
        Files.writeString(buildDir.resolve("ant.properties"), "product_name=p\noutput_dir=\${basedir}/out\n")
        val start = System.currentTimeMillis()
        val stale = zip(buildDir.resolve("out/p.zip"), start - 60_000)

        assertNull(BuildOutputLocator.find(buildDir, stale.toString(), stale.toString(), start))
    }

    @Test
    fun `protoc binDir accepts executable or its folder`() {
        val dir = tempBuildDir().resolve("bin")
        Files.createDirectories(dir)
        val protoc = Files.writeString(dir.resolve("protoc"), "#!/bin/sh\n")

        assertEquals(dir.toFile().absolutePath, ProtocConfig.binDir(protoc.toString()))
        assertEquals(dir.toFile().absolutePath, ProtocConfig.binDir(dir.toString()))
        assertNull(ProtocConfig.binDir(""))
        assertNull(ProtocConfig.binDir(dir.resolve("missing").toString()))
        assertNull(ProtocConfig.binDir(dir.parent.toString()))
        assertNull(ProtocConfig.binDir(File(dir.toFile(), "other").also { it.writeText("x") }.path))
    }

    @Test
    fun `protoc ant args set both protobuf properties`() {
        assertEquals(
            listOf("-Dprotobuf_local_dir=/opt/protoc/bin", "-Dprotobuf_dir=/opt/protoc/bin"),
            ProtocConfig.antArgs("/opt/protoc/bin")
        )
    }
}
