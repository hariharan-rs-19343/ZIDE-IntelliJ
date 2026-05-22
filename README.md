# ZIDE — IntelliJ IDEA Plugin

Zoho ZIDE development workflow for IntelliJ IDEA — create projects, manage Tomcat servers, deploy, debug, and iterate on code.

## Features

### Project Creation
- **New Project Wizard** — Create ZIDE projects from File > New > Project > ZIDE Project or ZIDE menu
- **CMTool Integration** — Browse and select services with auto-populated repository URLs
- **Full Setup Pipeline** — Git clone, build download (remote/local), WAR extraction, ANT hooks, deployment config patching, and `.zide_resources` scaffolding in one flow
- **Build Type Selection** — Remote Build (download via wget with progress) or Local Build (pick zip file)

### Server Management
- **Run / Debug** — Start Tomcat in foreground mode (`catalina.sh run`) or debug mode with JPDA auto-attach
- **Restart / Stop** — Restart or gracefully stop with multi-level fallback (process destroy → catalina.sh stop → lsof kill)
- **Auto-Cleanup** — Tomcat servers are killed when IntelliJ quits or restarts
- **Hot-Swap** — Java class changes are hot-swapped into the running JVM via JDWP on save

### Deployment
- **Update Deployment** — Custom Build (remote URL) or Local Build (zip file) with full extraction and hook pipeline
- **Deployment Properties** — Edit Host Name, IAM Server, ports, database type (MySQL/PostgreSQL), credentials, and schema
- **Deploy Sync on Save** — Automatically copy compiled classes, run ANT hooks, and sync resources on file save
- **Deployment Config Patching** — Automatically patches server.xml, web.xml, persistence, and security configs

### Settings (Settings > Tools > Zide)
- **CMTool** — Auth Token for CMTool API access
- **Wget Configuration** — Username/password for build downloads, auto-manages ~/.wgetrc
- **Git** — Git path (with auto-detect) and credentials
- **Zoho Repository** — Username and password

### Productivity
- **ZIDE Menu** — All actions with keyboard shortcuts
- **App Logs** — View last 5000 lines of application logs with color-coded ERROR/WARN highlighting
- **ANT Build** — Run ANT build scripts from the project directory
- **Password Visibility Toggle** — Eye button on all credential fields

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

## Tool Window: SAS-ZIDE

| Tab | Description |
|-----|-------------|
| **Servers** | Tree view of configured Tomcat servers with status indicators |
| **Output** | Console output from server start/stop, build, and deployment commands |
| **App Logs** | Application log viewer (`*application0.txt`) with refresh button |

### Toolbar Buttons (Servers Tab)
| Button | Description |
|--------|-------------|
| Add | Add a new Tomcat server |
| Remove | Remove the selected server |
| Refresh | Refresh all server statuses |
| Stop | Stop the selected running server |
| Restart | Restart the selected server (stop + start) |

## ZIDE Menu

Available under **ZIDE** in the main menu bar:

| Action | Shortcut | Description |
|--------|----------|-------------|
| New Project | | Create a new ZIDE project |
| Add Tomcat Server | | Add a new server (manual or ZIDE auto-config) |
| Edit Server | | Modify server settings |
| Run | `Ctrl+Shift+I` | Start Tomcat (`catalina.sh run`) |
| Debug | `Ctrl+Shift+D` | Start with JPDA and auto-attach debugger |
| Stop | `Ctrl+Shift+.` | Stop the running Tomcat process |
| Refresh | | Refresh all server statuses |
| Build | `Ctrl+Shift+B` | Run ANT build |
| Deployment Properties | | Edit ZIDE deployment properties |
| Update Deployment > Custom Build | `Ctrl+Shift+Alt+U` | Download and deploy from remote URL |
| Update Deployment > Local Build | `Ctrl+Shift+U` | Deploy from local zip file |
| App Logs | `Ctrl+Shift+L` | View application logs |

## New Project Creation Flow

1. Validates CMTool Auth Token (prompts if not configured)
2. Fetches services from CMTool API
3. User selects service, JDK, branch, and build source
4. Clones git repository
5. Downloads/extracts build into deployment folder
6. Scaffolds `.zide_resources/` (service.xml, zide_properties.xml, repository.properties)
7. Runs ANT hooks (pre-creation, post-creation, zide module)
8. Shows Deployment Properties dialog for configuration
9. Patches deployment configs
10. Opens project in IntelliJ
