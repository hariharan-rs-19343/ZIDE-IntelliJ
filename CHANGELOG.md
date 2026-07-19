# Changelog

All notable changes to the ZIDE IntelliJ plugin are documented here.

---

## [0.0.8] — 2026-07-19

### Fixed
- **Debug startup breakpoints** — `JPDA_SUSPEND=y` added to `TomcatManager.buildCatalinaEnvVars()`. The JVM now halts immediately after the JPDA socket opens and waits for the debugger before executing any code. Breakpoints in `ZhareHubService.start()`, `Util.setSystemProperties()`, and other startup methods are now hit reliably.
- **Debug attach order** — `DebugOnServerAction` now attaches the debugger after a short delay (3 seconds for the JPDA socket to initialize) instead of waiting for the server HTTP port to become available. The server resumes with the debugger already connected, so startup breakpoints are active.

---

## [0.0.7] — 2026-06-13

### Added
- **Kotlin 2.2.0 + Java 21** — upgraded build toolchain (Gradle 9.0, IntelliJ Platform Plugin 2.16.0, minimum IDE 2024.3).
- **Auto-configure project libraries** — all JARs from `WEB-INF/lib/` are added as an IntelliJ project library on server start.
- **Diagnostic logging** — deploy sync VFS events logged at INFO level in `idea.log`.

### Fixed
- **Deploy sync rewrite** — unified VFS listener handles file create, edit, move, and delete; copies/removes files in the deployment folder instantly.
- **Deploy sync server resolution** — uses `project.basePath` directly with `$PROJECT_DIR$` macro expansion; robust fallback to first server.
- **PostgreSQL reinit** — now executes bundled `postgres_functions.sql` (UNIX_TIMESTAMP, GROUP_CONCAT, pgcrypto, citext) after schema creation.
- **Uninstall order** — project directory is deleted before closing the project and showing the Welcome Screen.

### Changed
- Project `.iml` uses `inheritedJdk` — no longer hardcodes JDK name.
- SSL keystore and HTTPS connector patching disabled (project creation handles it).

---

## [0.0.6]

### Added
- **Build before server start** — full project compilation runs before Tomcat launches.
- **Auto hot-swap in debug mode** — class changes applied automatically via JDWP without a confirmation dialog.

### Fixed
- **Deploy-sync overhaul** — compiler output now points directly to `WEB-INF/classes/`.
- **Smart webapp directory detection** — reads `docBase` from `server.xml`, scans `webapps/` when `PARENT_SERVICE` doesn't match.
- **Startup order fix** — deployment config patching runs after `server.xml` sync.
- **Clean deployment on Update** — old webapp directory deleted before extracting new WAR; Tomcat `work/` cache cleared on every start.
- Existing deployments with `reloadable="false"` automatically migrated to `"true"`.

---

## [0.0.5]

### Added
- **New Project Wizard** — create ZIDE projects from File > New > Project or ZIDE menu with CMTool service selection, git clone, build download, and full deployment setup.
- **CMTool API integration** — fetches products list with `PRIVATE-TOKEN` auth, auto-populates repository URLs.
- **Run Hooks action** — manually run precreation, postcreation, and zidemodule ANT hooks.
- **Deployment Properties dialog** — edit Host Name, IAM Server, ports, and database configuration.

---

## [0.0.4] and earlier

- Initial Tomcat server management (add, run, debug, stop, restart).
- Deploy Sync on Save with ANT hook integration.
- App Logs viewer with color-coded ERROR/WARN output.
- Settings: CMTool Auth Token, Wget credentials, Git path, Zoho Repository.
- Auto-update check from GitHub releases on startup.
