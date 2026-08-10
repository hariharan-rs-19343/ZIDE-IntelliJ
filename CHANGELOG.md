# Changelog

All notable changes to the ZIDE IntelliJ plugin are documented here.

---

## [0.0.9] — 2026-08-10

### Fixed
- **Debug attach "Connection refused"** — debugger attach no longer uses a fixed 3s delay. It polls with `lsof` until the JPDA port is bound (up to 60s), then attaches. Avoids attaching before pre-start compile/patch finishes.
- **Stop leaves debug port alive** — Stop disconnects the IDE Remote debug session, removes the temporary `Debug {server}` run config, and force-kills the JDWP port if it survives process teardown.
- **404 after compiler-output redirect** — when module output points at deployment `WEB-INF/classes`, IntelliJ’s “Clear output directory on rebuild” is disabled so WAR-deployed product classes are not wiped on Rebuild.
- **Premature hot-swap on Java save** — removed save-time `triggerHotSwap()` that raced compilation; rely on `RUN_HOTSWAP_ALWAYS` after compile completes.
- **Wrong debug module on multi-module projects** — Remote debug config prefers the module whose compiler output is `WEB-INF/classes` instead of `modules.first()`.

### Changed
- **Create-time compiler output** — new projects write `.iml` with `inherit-compiler-output="false"` and output URL set to deployment `WEB-INF/classes` when the webapp exists; live ModuleRootManager redirect runs after Tomcat registration.
- **Safer webapp output path** — compiler redirect only applies when `webapps/{service}/WEB-INF` already exists (no phantom empty webapp directories).

---

## [0.0.8] — 2026-07-19

### Fixed
- **Debug startup breakpoints** — `JPDA_SUSPEND=y` added to `TomcatManager.buildCatalinaEnvVars()`. The JVM now halts immediately after the JPDA socket opens and waits for the debugger before executing any code. Breakpoints in `ZhareHubService.start()`, `Util.setSystemProperties()`, and other startup methods are now hit reliably.
- **Debug attach order** — `DebugOnServerAction` now attaches the debugger after a short delay (3 seconds for the JPDA socket to initialize) instead of waiting for the server HTTP port to become available. The server resumes with the debugger already connected, so startup breakpoints are active.

### Changed
- **Eclipse create/patch parity** — create order is pre hooks → module → post/zide hooks → props dialog → open + auto Tomcat register; config replace runs at start with `ZIDE.DO_REPLACE` gating.
- **Removed invented keystore/HTTPS connector patching** — plugin never downloads or overwrites `sas.keystore` (App. Server container owns SSL).
- **Data-driven Replacer** — product `install.xml` / `install.properties` at launch, with hardcoded `DeploymentConfigPatcher` fallback (HTTP port included).
- **Wizard parity** — product download URL, MI deployment, runnable services, start-after-create; `DependencyLinker` on create.
- **Full Tomcat version** in server name (e.g. `9.0.120.0`).

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
- SSL keystore and HTTPS connector patching removed (not Eclipse behavior).

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
