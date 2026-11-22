# Changelog

All notable changes to the ZIDE IntelliJ plugin are documented here.

---

## [0.1.6] — 2026-11-22

### Added
- **Build and Update Deployment** (ZIDE menu after Build, Ctrl+Shift+Alt+B) — runs the ANT build in `build/` and deploys the zip it produces in one step.
  - Build output streams into the SAS-ZIDE **Output** tab. The build can be cancelled, and nothing is deployed if it fails or is cancelled.
  - The zip is found automatically: `${output_dir}/${product_name}.zip` from `build/ant.properties`, the newest zip in `output_dir`, or `ZIDE.DOWNLOAD_URL` in `service.xml` when it is a local path. Only zips created after the build started are used, so an old build is never deployed.
  - If the zip can't be found, a file chooser opens in the output folder. The pick is remembered for future builds of that project.
  - The server is chosen before the build starts, and deployment then follows the same steps as Update Deployment → Local Build.
- **Custom protoc for ANT builds** — Settings → Tools → Zide → "Use custom protoc for ANT builds" (default off). When on, a protoc path is required: the `protoc` file or its `bin` folder.
  - Build and Build and Update Deployment pass `-Dprotobuf_local_dir` and `-Dprotobuf_dir` to ANT and put the folder first on `PATH`. `build/ant.properties` is not edited.
  - If the path becomes invalid, builds stop with an error linking to Settings.

### Fixed
- **Git clone with Settings credentials** — when Settings → Tools → Zide → Git has a username and password/token, New Project clones with them in the URL (`https://user:token@host/repo.git`, URL-encoded). When blank, git uses the system credential (osxkeychain) as before.
  - Git's credential helpers are off for that clone, so the token is never saved to the keychain.
  - After the clone, `origin` is reset to the plain URL, so the token is not left in `.git/config`. Progress and error messages never show it.

---

## [0.1.5] — 2026-11-22

### Added
- **Global credential fallback** — values in Settings → Tools → Zide always win. When a field is blank, the plugin uses the credential already on the machine:
  - CMTool → `~/.zide/zide.conf` (`zide.cmtools.authtoken`, shared with Eclipse ZIDE)
  - Wget → `~/.wgetrc`, then `~/.netrc`
  - Git → git's credential helper (e.g. osxkeychain), then `~/.netrc`
  - Zoho Repository → `~/.hgrc` `[auth]`, then `~/.netrc`
- **Credential source hint** — each credential page in Settings shows which source will be used. CMTool and Wget show red when nothing is found.

### Changed
- **CMTool and Wget are required** — New Project and Custom Build stop with an error dialog and an **Open Settings** button when neither Settings nor the global file has them. Startup shows a warning with the same link. The old CMTool token input box is gone.
- **Settings credentials override stale global ones** — a Git username/password in Settings is used for the clone instead of the keychain. Wget credentials in Settings are used even if `~/.wgetrc` has older values. The plugin only reads the system credential store and never writes to it.
- **Zoho Repository credential is now used** — it is tried first when downloading the ZIDE config repository.
- **Clearer login errors** — failed git clones and wget downloads say which credential was rejected (Settings, keychain, `~/.wgetrc`, …).

---

## [0.1.4] — 2026-11-20

### Fixed
- **install.xml XML replacements match Eclipse ZIDE** — regex rules now apply to the element's attributes, not its text, and `newAttribute` rules add an attribute. Before, server.xml rules (HTTP/HTTPS ports, SSL connector, `deployOnStartup`) did nothing, and `newAttribute` rules wiped the element's children (e.g. the `<Host>` Valves) in `conf/server.xml.orig`.
- **Replacements are stable across starts** — XML files are written without re-indenting, so repeated starts no longer add blank lines.

### Changed
- **server.xml built from install.xml on every start (no Eclipse needed)** — like Eclipse `createTomcatSetup`, `conf/server.xml.orig` (after install.xml) is copied to `conf/server.xml`, then Context, shutdown port, `deployOnStartup` and HTTP port are applied. This runs on every start, even when replacement is already done.
- **`Servers/<service>-config` is a fallback only** — Eclipse WTP's `server.xml` copy is used only when the deployment has no `conf/server.xml.orig`.
- **Tomcat home from ZIDE config** — config patching uses `ZIDE.DEPLOYMENT_FOLDER` + `deploy.folder.basepath` (module `Zide.properties`) instead of a hardcoded `AdventNet/Sas/tomcat`. A warning is logged if it differs from the server path.
- **Webapp server.xml copy happens last** — `webapps/<service>/WEB-INF/conf/server.xml` gets the final `conf/server.xml`. Run configurations also get it now.

### Upgrade note
If a deployment was started with 0.1.3 or earlier, its `conf/server.xml.orig` may be damaged (missing Valves, `deployOnStartup=false` written as text inside `<Host>`). Restore it from the build zip or run **Update Deployment** before starting.

---

## [0.1.3] — 2026-10-22

### Added
- **ZeroTrust (Zero Agent) proxy** — when `ZERO_HELPER_STATUS=ON` is set in `~/.zshrc` / `~/.bash_profile` / `~/.bashrc` (or the Windows environment), the plugin routes traffic through the Zero Agent at `127.0.0.1:3128`. Only `localhost` and `127.0.0.1` bypass it.
  - The IntelliJ HTTP proxy is set at project open, which also covers CMTool and update checks.
  - git, hg, wget, and ANT child processes get `http_proxy` / `https_proxy` / `no_proxy`.
  - Tomcat gets `launch.proxy.vmargs` from the module `Zide.properties`, falling back to `zide/deployment/zide/Zide.properties`. It is added to `CATALINA_OPTS` once and skipped if `-Dhttp.proxyHost` is already set.
- **Restore previous IDE proxy** — the IntelliJ proxy that was in place before the plugin changed it is saved and restored when the Zero Agent is later off. A proxy you changed by hand in the meantime is left alone.
- **Settings → Tools → Zide → "Use ZeroTrust proxy"** — default on. Turn it off to leave all proxy settings untouched. `-Dzide.unalterproxy=true` also skips IDE proxy changes (Eclipse parity).

---

## [0.1.2] — 2026-10-05

### Fixed
- **M19 install.xml database name** — the database name is taken from `.zide_resources/zide_properties.xml` (`ZIDE_DB_NAME`) instead of a stale `configuration.properties`. Server start stops with a clear error when the name is missing, instead of starting against the wrong database.
- **Eclipse-compatible replacements** — install.xml text and XML replacements use Eclipse regex semantics, so recipes patch the same values as Eclipse ZIDE.

---

## [0.1.1] — 2026-08-23

### Fixed
- **Debug trigger on running server** — triggering Debug while the server is already running now stops it first, waits for the port to free (up to 15s), then starts in debug mode. Previously it silently skipped the debug start ("Skipping debug start").
- **Output console clears on start/restart** — the SAS-ZIDE Output tab is cleared at the beginning of each server start and restart, matching the behavior of the Build and Update Deployment actions.

### Changed
- **ANT build via IntelliJ Terminal** — the Build action now opens a new "ZIDE Build" tab in IntelliJ's built-in Terminal, running ANT with the project `build/` as the working directory. Ctrl+C kills the build; full terminal scroll-back and copy/paste are available.

---

## [0.1.0] — 2026-08-19

### Added
- **ZIDE config repo clone** — clones `https://cmsuite.csez.zohocorpin.com/eclipse/zide` into `{workspace}/zide` (Eclipse `cloneZideProject` parity). Create aborts if clone fails. `install.xml` recipes are read from `zide/deployment/{moduleDir}/{deployType}/`.
- **Clear App Logs** — ZIDE menu and App Logs toolbar. Shortcut **Option+Shift+K** (Alt+Shift+K). Deletes `*application0.txt` and clears the App Logs console. Blocked with an error dialog while the server is running.

### Fixed
- **Clone cancel leaked credentials** — canceling `hg clone` is treated as cancel (not failure). Progress and error text redact `user:pass@` from URLs.
- **Read-only Deployment Properties** — window X and Escape dismiss the dialog; a single Close button (no disabled Close/Done pair).
- **Cancel during New Project** — product dir and `deployment/{name}` are rolled back; New Project Wizard window is closed. Shared `{workspace}/zide` is kept. No “Failed to create project” on cancel.
- **Clear App Logs icon** — uses `AllIcons.Actions.GC` (the previous `ClearConsole` icon does not exist and crashed the ZIDE menu).

### Changed
- **install.xml replace root** — replacements apply against Tomcat home (`AdventNet/Sas/tomcat`), not the raw deployment folder. Catalog `@moduledir` comes from `{workspace}/zide/.zide_resources/zide_config.xml`.

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
