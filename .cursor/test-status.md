# ZIDE-IntelliJ — Feature Test Status

> **How to use**: After manually testing a feature, report the result here. I'll update the status in the matrix and append an entry to the history log.
>
> **Status values**: `PASS` | `FAIL` | `PARTIAL` | `UNTESTED` | `REGRESSED`

---

## Feature Test Matrix

| # | Feature | Since | Status | Last Tested | Notes |
|---|---------|-------|--------|-------------|-------|
| **Server Lifecycle** | | | | | |
| 1 | Server Run (catalina.sh run) | v0.0.1 | UNTESTED | — | |
| 2 | Server Debug (catalina.sh jpda run + auto-attach) | v0.0.1 | UNTESTED | — | |
| 3 | Server Stop (process destroy + force kill) | v0.0.1 | UNTESTED | — | |
| 4 | Server Restart | v0.0.2 | UNTESTED | — | |
| 5 | Add / Edit / Remove Server | v0.0.1 | PASS | 2026-06-13 | |
| 6 | Build before start | v0.0.6 | UNTESTED | — | |
| 7 | Pre-start setup (sync server.xml, clean work dir) | v0.0.2 | UNTESTED | — | |
| **Deploy Sync** | | | | | |
| 8 | .java save → auto-compile to WEB-INF/classes | v0.0.6 | UNTESTED | — | Compiler output redirect |
| 9 | .java save → JDWP hot-swap (debug mode) | v0.0.4 | UNTESTED | — | |
| 10 | Non-Java save → ANT hook tasks | v0.0.1 | PASS | 2026-06-14 | |
| 11 | Non-Java save → auto-resource copy | v0.0.1 | PASS | 2026-06-14 | Fixed: switched from beforeDocumentSaving to VFileContentChangeEvent |
| 12 | File creation → deploy sync trigger | v0.0.7 | PASS | 2026-06-14 | Fixed: use event.path instead of event.file |
| 13 | File move → deploy sync trigger | v0.0.7 | UNTESTED | — | |
| 14 | Deploy sync diagnostic logging | v0.0.7 | PASS | 2026-06-14 | INFO-level logs in idea.log |
| 15 | File deletion → deploy sync trigger | v0.0.7 | PASS | 2026-06-14 | New: handleFileDelete() |
| **Config Patching** | | | | | |
| 16 | server.xml — Context element, reloadable, shutdown port | v0.0.2 | UNTESTED | — | |
| 17 | web.xml — JSP servlet for dev | v0.0.2 | UNTESTED | — | |
| 18 | persistence-configurations.xml — DB config | v0.0.2 | UNTESTED | — | |
| 19 | security-properties.xml — IAM, service name | v0.0.2 | UNTESTED | — | |
| 20 | configuration.properties — DB driver, URL, creds | v0.0.2 | UNTESTED | — | |
| 21 | HTTPS Connector (port 8443, SSL) | v0.0.7 | UNTESTED | — | |
| 22 | SSL keystore download (sas.keystore) | v0.0.7 | UNTESTED | — | |
| **New Project Creation** | | | | | |
| 23 | New Project Wizard (sidebar entry) | v0.0.5 | PASS | 2026-06-13 | |
| 24 | CMTools API — fetch products | v0.0.5 | PASS | 2026-06-13 | |
| 25 | Git clone (repository) | v0.0.5 | PASS | 2026-06-13 | |
| 26 | Build download (wget) | v0.0.5 | PASS | 2026-06-13 | |
| 27 | Concurrent clone + download | v0.0.7 | PASS | 2026-06-13 | |
| 28 | ANT hooks (precreation, postcreation, zidemodule) | v0.0.5 | PASS | 2026-06-13 | |
| 29 | Project Wizard Dialog (legacy) | v0.0.5 | PASS | 2026-06-13 | |
| **Update Deployment** | | | | | |
| 30 | Custom Build (remote URL download) | v0.0.5 | PASS | 2026-06-13 | |
| 31 | Local Build (zip file) | v0.0.5 | PASS | 2026-06-13 | |
| 32 | Extraction + hook pipeline | v0.0.5 | PASS | 2026-06-13 | |
| **Database** | | | | | |
| 33 | Database Reinit (PostgreSQL) | v0.0.7 | PASS | 2026-06-13 | |
| 34 | Database Reinit (MySQL) | v0.0.7 | PASS | 2026-06-13 | |
| 35 | SQL File Migration | v0.0.7 | UNTESTED | — | |
| **Dependencies** | | | | | |
| 36 | Dependency Linker (WEB-INF/lib) | v0.0.7 | UNTESTED | — | |
| 37 | Auto-configure project libraries on open | v0.0.7 | UNTESTED | — | |
| **Settings** | | | | | |
| 38 | CMTool Auth Token panel | v0.0.5 | PASS | 2026-06-13 | |
| 39 | Wget Configuration panel | v0.0.5 | PASS | 2026-06-13 | |
| 40 | Git Configuration panel | v0.0.5 | PASS | 2026-06-13 | |
| 41 | Zoho Repository panel | v0.0.5 | PASS | 2026-06-13 | |
| **Tool Window** | | | | | |
| 42 | Servers tab (tree view, status icons) | v0.0.1 | PASS | 2026-06-13 | |
| 43 | Output tab (console log streaming) | v0.0.1 | PASS | 2026-06-13 | |
| 44 | App Logs tab (tail application log) | v0.0.1 | PASS | 2026-06-13 | |
| **Other** | | | | | |
| 45 | Auto-update checker (GitHub releases) | v0.0.5 | PASS | 2026-06-13 | |
| 46 | Plugin download + install | v0.0.5 | PASS | 2026-06-13 | |
| 47 | Deployment Properties dialog | v0.0.5 | PASS | 2026-06-13 | |
| 48 | Keyboard shortcuts (Run, Debug, Stop, Build) | v0.0.3 | PASS | 2026-06-13 | |
| 49 | ZIDE context menu (right-click) | v0.0.7 | PASS | 2026-06-13 | |
| 50 | Proxy support (~/.dzide/proxy.properties) | v0.0.7 | UNTESTED | — | |
| 51 | Run Configuration integration | v0.0.1 | UNTESTED | — | |
| **Build Toolchain (v0.0.7)** | | | | | |
| 52 | Kotlin 2.2.0 compilation | v0.0.7 | UNTESTED | — | Upgraded from 1.9.25 |
| 53 | Java 21 target | v0.0.7 | UNTESTED | — | Upgraded from 17 |
| 54 | Gradle 9.0 build | v0.0.7 | UNTESTED | — | Upgraded from 8.12 |
| 55 | IntelliJ 2024.3+ compatibility | v0.0.7 | UNTESTED | — | sinceBuild = 243 |

---

## Test History Log

<!-- Append new test sessions below. Format:
### YYYY-MM-DD — vX.X.X
- [STATUS] Feature name — optional notes
-->

### 2026-06-13 — v0.0.7 (initial)
- All 54 features listed as UNTESTED
- Awaiting first manual test session

### 2026-06-13 — v0.0.7
- [PASS] Database Reinit (PostgreSQL)
- [PASS] Database Reinit (MySQL)
- [PASS] Add / Edit / Remove Server
- [PASS] New Project Wizard (sidebar entry)
- [PASS] CMTools API — fetch products
- [PASS] Git clone (repository)
- [PASS] Build download (wget)
- [PASS] Concurrent clone + download
- [PASS] ANT hooks (precreation, postcreation, zidemodule)
- [PASS] Project Wizard Dialog (legacy)
- [PASS] Custom Build (remote URL download)
- [PASS] Local Build (zip file)
- [PASS] Extraction + hook pipeline
- [PASS] CMTool Auth Token panel
- [PASS] Wget Configuration panel
- [PASS] Git Configuration panel
- [PASS] Zoho Repository panel
- [PASS] Servers tab (tree view, status icons)
- [PASS] Output tab (console log streaming)
- [PASS] App Logs tab (tail application log)
- [PASS] Auto-update checker (GitHub releases)
- [PASS] Plugin download + install
- [PASS] Deployment Properties dialog
- [PASS] Keyboard shortcuts (Run, Debug, Stop, Build)
- [PASS] ZIDE context menu (right-click)

### 2026-06-14 — v0.0.7 (deploy sync fixes)
- [PASS] Non-Java save → ANT hook tasks
- [PASS] Non-Java save → auto-resource copy — fixed: switched from beforeDocumentSaving to VFileContentChangeEvent
- [PASS] File creation → deploy sync trigger — fixed: use event.path instead of event.file
- [PASS] File deletion → deploy sync trigger — new: handleFileDelete()
- [PASS] Deploy sync diagnostic logging — INFO-level logs in idea.log
