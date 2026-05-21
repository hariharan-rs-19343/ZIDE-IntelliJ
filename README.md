# ZIDE — IntelliJ IDEA Plugin

Tomcat server management and ZIDE integration for IntelliJ IDEA.

## Features

- **Server Management** — Add, edit, remove Tomcat servers from the SAS-ZIDE tool window
- **Run / Debug** — Start Tomcat in foreground mode (`catalina.sh run`) or debug mode with JPDA auto-attach
- **Restart** — Restart a running server with a single click from the toolbar
- **Stop** — Gracefully stop the attached Tomcat process with multi-level fallback
- **Deployment Properties** — View and edit ZIDE deployment properties (Host Name, IAM Server, Schema, ports); read-only when server is running
- **Deployment Config Patching** — Automatically patches server.xml, web.xml, persistence, and security configs before server start
- **Pre-Start Setup** — Runs postzidedeploy.sh and syncs server.xml files between directories before launch
- **Build** — Run ANT build scripts from the project directory
- **Update Deployment** — Deploy a zip file to the ZIDE deployment folder with full pipeline (extract, ANT hooks, config patching)
- **App Logs** — View application logs (last 5000 lines) with color-coded ERROR/WARN highlighting
- **Deploy Sync on Save** — Automatically copy compiled classes, run ANT hooks, and sync resources on file save
- **Hot-Swap** — Java class changes are hot-swapped into the running JVM via JDWP when a debug session is active
- **ZIDE Auto-Configuration** — Detect and import Eclipse ZIDE project settings (`.zide_resources/`)
- **Auto-Cleanup** — Tomcat servers are killed when IntelliJ quits or restarts

## Requirements

- IntelliJ IDEA 2024.1.7+ (Community or Ultimate)
- Java 17+
- Gradle 8.12+

## Building

```bash
./gradlew clean buildPlugin
```

The plugin zip will be in `build/distributions/zide-intelliJ-plugin-{version}.zip`.

## Running in Sandbox

```bash
./gradlew runIde
```

This launches a sandboxed IntelliJ IDEA instance with the plugin installed.

## Tool Window Tabs

| Tab | Description |
|-----|-------------|
| **Servers** | Tree view of configured Tomcat servers with status indicators |
| **Output** | Console output from server start/stop and build commands |
| **App Logs** | Application log viewer (`*application0.txt`) with refresh button |

## Toolbar Buttons

### Servers Tab
| Button | Description |
|--------|-------------|
| Add | Add a new Tomcat server |
| Refresh | Refresh all server statuses |
| Stop | Stop the selected running server |
| Restart | Restart the selected server (stop + start) |

### App Logs Tab
| Button | Description |
|--------|-------------|
| Refresh | Reload the latest application log file |

## ZIDE Menu

Available under **ZIDE** in the main menu bar:

| Action | Shortcut | Description |
|--------|----------|-------------|
| Add Tomcat Server | | Add a new server (manual or ZIDE auto-config) |
| Edit Server | | Modify server settings |
| Remove Server | | Delete a server configuration |
| Run | `Ctrl+Shift+I` | Start Tomcat (`catalina.sh run`) |
| Debug | `Ctrl+Shift+D` | Start with JPDA and auto-attach debugger |
| Stop | `Ctrl+Shift+.` | Stop the running Tomcat process |
| Refresh | | Refresh all server statuses |
| Build | `Ctrl+Shift+B` | Run ANT build |
| Update Deployment | `Ctrl+Shift+U` | Deploy zip to server |
| Deployment Properties | | Edit ZIDE deployment properties |
| App Logs | `Ctrl+Shift+L` | View application logs |
