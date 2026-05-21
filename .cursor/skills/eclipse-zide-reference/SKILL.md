---
name: eclipse-zide-reference
description: >-
  Eclipse ZIDE plugin architecture and behavior reference for the IntelliJ ZIDE plugin
  (com.zoho.dzide). Use when modifying project creation, launch configurations, code checks,
  commit integration, deployment management, hooks, config parsing, or any feature that
  mirrors the Eclipse ZIDE plugin (com.zoho.zide v2.4.7). Triggers on work in
  src/main/kotlin/com/zoho/dzide/ or when the user mentions Eclipse ZIDE, service creation,
  zide_config.xml, service.xml, zide_properties.xml, deployment patching, code checks, or
  commit checks.
---

# Eclipse ZIDE Plugin Reference for IntelliJ Development

This skill contains the complete architecture and behavior of the Eclipse ZIDE plugin
(`com.zoho.zide` v2.4.7) so you can maintain and extend the IntelliJ ZIDE plugin
(`com.zoho.dzide`) without needing the Eclipse decompiled source.

For detailed field references and XML schemas, see [reference.md](reference.md).

## Plugin Identity

- **Bundle:** `com.zoho.zide` (singleton), JavaSE-17, lazy activation
- **Activator:** `com.zoho.zide.activator.Activator` (bundle start/stop)
- **Startup:** `PluginStarter.earlyStartup()` -- proxy config, formatter, launch listeners, service XML updates, VM startup

## Package Architecture (35 packages, ~250 classes)

| Package | Key Classes | Purpose |
|---|---|---|
| `activator` | Activator, PluginStarter | Plugin lifecycle, earlyStartup tasks |
| `codeassistant` | CAMenuHandler, ChatMessageHandler | AI chat panel (OpenAI-compatible) |
| `codecheck` | CodeCheck, CodeCheckRunner, CommitChecker, 15+ check classes | Code quality checks with Eclipse markers |
| `command` | ZideCommand, ProcessWrapper | Shell/process execution |
| `config.model` | **Service**, **ServiceConfig** | Core service creation/update/rename engine |
| `configuration` | TextReplacer, XMLReplacer, ReplacerFactory | Config file variable substitution |
| `core.launching` | LaunchUtil, 7 delegate classes | Launch configuration creation |
| `dbutil` | ConfigApiUtil, ConfigDBUtil, MysqlDBUtil | CMTools REST API, DB operations |
| `diagnostics` | NetworkCheckGroup, CheckRunnerUI | Network diagnostics with fixes |
| `hg` | ZideMercurialRepository, HgIgnoreHandler | Mercurial integration |
| `repository` | GitRepository, HgRepository, RepositoryFactory | VCS abstraction (repoType: 1=HG, 2=Git) |
| `repository.commitcheck` | ZideCommitAction, CommitChecker | Pre-commit code check enforcement |
| `repository.ignore` | GitIgnoreHandler, HgIgnoreHandler | .gitignore/.hgignore management |
| `resource` | ResourceHandler, HttpFileProvider | Multi-threaded HTTP download with resume |
| `scheduler` | SchedulerStartup, AutomaticUpdateJob | Auto-update scheduling |
| `ui.wizards.pages` | CreateServicePage, WorkingSetPage, RunnableServicePage | Wizard UI pages |
| `util` | ServiceAPI, ZideConfigAPI, ZidePropertiesAPI, FileUtils | Core utilities |
| `vm` | VagrantExecutor, ZideDockerService | VM/Docker management |

## Service Creation Flow (19 Steps)

The Eclipse `Service.create()` method in `config.model.Service` runs these steps:

1. **Init** -- log start, add to temp list, resolve repo type (1=HG, 2=Git)
2. **Clone repository** -- `RepositoryFactory.getRepository()` -> HG share or full clone / Git clone
3. **Add ZideProjectNature** -- marks project for Zide menus/testers (IntelliJ: not needed)
4. **Create `.zide_resources/`** -- metadata folder (derived)
5. **Add ignore entries** -- `.hgignore` (with `re:` prefix) or `.git/info/exclude` patterns
6. **Download + extract build** -- HTTP multi-threaded download, zip/tgz extraction, WAR extraction for M19
7. **Create `service_info.xml`** -- 20+ metadata fields (see reference.md)
8. **Create JUnit info** -- `junit_status.txt`, `junit_checklist.txt`
9. **Set Java compilation prefs** -- `zide.pref.enable_java_compilation_check`
10. **Pre-creation hooks** -- `ProjectHook.preCreation()` via AntHookRunner
11. **Create natures/facets/classpath** -- Java natures, WTP facets, source entries, user libraries from deployment JARs, JRE container, project dependencies, Java compliance level
12. **Set path variable** -- `{SERVICE_NAME}_DEPLOYMENT_PATH`
13. **Create launch config** -- Tomcat (WTP) or standalone Java launch via LaunchUtil
14. **Create builder config** -- Ant builder for `build/ant.properties`
15. **Create Zide properties** -- deployment properties (IAM URL, ports, DB)
16. **Update services menu** -- refresh dynamic "Zide Services" menu
17. **Post-creation hooks** -- `ProjectHook.postCreation()`
18. **Zide-module hooks** -- `ProjectHook.zideModuleHookCreation()`
19. **Log completion** -- timing info

**Rollback on failure:** deletes project, deployment folder, launch configs, Tomcat server/runtime.

## Launch System

7 launch configuration types, each with a delegate and classpath provider:

| Type | Delegate | Purpose |
|---|---|---|
| Zide Services | ZohoLaunchConfigurationDelegate | Standalone Java service launch |
| Zide Tomcat | ZohoTomcatConfigurationDelegate | Tomcat WTP launch |
| LookingGlass | LookingGlassLaunchConfigurationDelegate | Test automation |
| Hacksaw | HacksawLaunchConfigurationDelegate | Security scan |
| UDE | UDELaunchConfiguration | User Data Emulation |
| JUnit | JunitLaunchConfiguration | Test runner |
| Builder | (via LaunchUtil) | Ant build |

`LaunchUtil` resolves: main class, VM args, program args, classpath, source paths from `Zide.properties`.

## Code Check Framework

`CodeCheck` interface: `getResource()`, `getMarkerType()`, `getViolations()`, `isValidExtension()`, `isExcluded()`

15+ checks: I18NCheck, PMDCheck, SecurityCheck, DollarIDCheck, JSCheck, JsHintCheck, CtrlMCheck, JavaPackageCheck, ZohoKeywordCheck, PropertiesStandardCheck, SMCCheck, WrapperCheck, HacksawReportMarker, CustomCheckMarker, JunitCheckMarker.

`CodeCheckRunner` orchestrates all checks, creates Eclipse IMarker instances. `CodeCheckScheduler` triggers on resource changes or schedule.

## Commit Integration

`ZideCommitAction` overrides EGit's commit action:
1. For each staged resource: `CommitChecker.canCommit()` checks for unresolved markers
2. Exclude patterns from `.zide/codecheck.conf` are applied
3. Legal declaration enforced: `LICENSE COMPATIBLE:YES` required
4. Java compilation errors block commit if enabled
5. JUnit test execution required if enabled

## Configuration System

**`zide_config.xml`** (8000+ lines): centralized service definitions with deployment, library, launch, and hook configuration per service. Parsed by `ZideConfig` singleton.

**`Zide.properties`**: per-module properties at `deployment/{moduleDir}/{deployType}/Zide.properties`. Contains launch VM args, classpath config, hook tasks, auto-resource-copy mappings.

**Config Replacers**: `TextReplacer` (find-and-replace), `XMLReplacer` (XPath-based), `ReplacerFactory` selects by type. Variables: `{HOSTNAME}`, `{HTTP_PORT}`, `{HTTPS_PORT}`, `{IAM_URL}`, DB credentials.

## Hooks System

Hooks defined per-service in the zide config repository. Executed via `AntHookRunner`:
- **precreationhook** -- before natures/facets
- **postcreationhook** -- after full setup
- **zidemodulehook** -- module-level hooks
- **postservicetarget** -- used during deployment updates (with DB properties)

Hook build.xml location: `.zide_resources/zide_hook/build.xml`
Properties passed: `REPOSITORY_PATH`, `DEPLOYMENT_PATH`, `ZIDE.PARENT_SERVICE`

## Eclipse-to-IntelliJ Mapping

| Eclipse | IntelliJ |
|---|---|
| BundleActivator | ProjectActivity / StartupActivity |
| IProjectNature | Facet or marker file check |
| Launch Configuration | RunConfiguration + ConfigurationType |
| Launch Delegate | RunProfileState / CommandLineState |
| Classpath Provider | Module dependencies |
| IMarker | ExternalAnnotator / LocalInspectionTool |
| IMarkerResolution | IntentionAction / QuickFix |
| Property Tester | AnAction.update() |
| Preference Page | Configurable (applicationConfigurable EP) |
| Property Page | ProjectConfigurable |
| Command + Handler | AnAction |
| View (ViewPart) | ToolWindowFactory |
| SWT Browser | JBCefBrowser (JCEF) |
| Eclipse Job | Task.Backgroundable |
| ResourceChangeListener | BulkFileListener |
| IStartup | postStartupActivity |
| Working Set | Scopes / Module Groups |
| SWT Dialog | DialogWrapper |
| SWT Wizard | ModuleBuilder / multi-step DialogWrapper |

## Deployment Folder Structure

```
{WORKSPACE}/deployment/{SERVICE_NAME}/
  AdventNet/Sas/tomcat/          (Tomcat-based M19 services)
    bin/catalina.sh
    conf/server.xml
    lib/
    webapps/{SERVICE_NAME}/      (extracted from ROOT.war)
      WEB-INF/
        classes/
        lib/
        conf/Persistence/persistence-configurations.xml
        security-properties.xml
        web.xml
  AdventNet/Sas/                 (standalone services)
    lib/
    bin/
```

## Key Behavioral Notes

- `isWarModel()` returns true when `deployType == "M19"` (Tomcat deployment)
- `isDeployable()` checks if the service has a download URL in zide_config.xml
- Runnable services are sorted with ZOHOACCOUNTS first
- Credential stripping: HgrcCredentialUtil removes passwords from `.hgrc` and git origin
- Download supports resume: if interrupted, user prompted to continue
- Proxy bypass hosts: `cm-server.csez.zohocorpin.com, cmsuite.csez.zohocorpin.com, cmtools.csez.zohocorpin.com, build.zohocorp.com, git.csez.zohocorpin.com, zide, zide.csez.zohocorpin.com`
