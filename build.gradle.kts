plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")

    // Database drivers for reinit and DD migration
    implementation("org.postgresql:postgresql:42.7.2")
    implementation("com.mysql:mysql-connector-j:9.1.0")

    // VCS integration
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.0.0.202409031743-r")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.zoho.intellij.zide"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
        vendor {
            name = "Zoho"
        }
        description = """
            ZIDE — Zoho's internal development workflow for IntelliJ IDEA. Create projects, manage Tomcat servers, deploy, debug, and iterate on code.
            <ul>
                <li>New Project Wizard — create ZIDE projects with CMTool service selection, git clone, and build deployment</li>
                <li>Server Management — Add, run, debug, stop, and restart Tomcat servers with JPDA auto-attach</li>
                <li>Update Deployment — Custom Build (remote URL) or Local Build (zip file) with ANT hooks</li>
                <li>Deploy Sync on Save — auto-compile, hot-swap, ANT hooks, and resource copy on file save</li>
                <li>Deployment Properties — edit Host Name, IAM Server, ports, and database configuration</li>
                <li>Settings — CMTool Auth Token, Wget credentials, Git path, and Zoho Repository</li>
                <li>Auto-Update — checks GitHub releases for newer versions on startup</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    wrapper {
        gradleVersion = "8.12"
    }

    named<Zip>("buildPlugin") {
        archiveBaseName.set("zide-intelliJ-plugin")
    }
}
