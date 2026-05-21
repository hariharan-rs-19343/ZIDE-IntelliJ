# Eclipse ZIDE Plugin -- Detailed Reference

## .zide_resources/ Complete Layout

The IntelliJ plugin creates this structure during new project creation:

```
.zide_resources/
  service.xml              -- Service metadata (20+ ZIDE.* fields)
  zide_properties.xml      -- Deployment properties (host, ports, IAM, DB)
  repository.properties    -- repositorypath=<absolute project path>
  zide_hook/
    build.xml              -- ANT targets: precreationhook, postcreationhook, zidemodulehook
  zide_build/
    build.xml              -- ANT target: postservicetarget (deploy-sync + update deployment)
```

If the cloned repository already contains these files, the plugin preserves them and does not overwrite.

## service.xml / service_info.xml Field Reference

The Eclipse plugin writes these fields to `.zide_resources/service_info.xml` (or `service.xml` in IntelliJ convention) during project creation:

| Field | Example Value | Description |
|---|---|---|
| `ZIDE.REPOSITORY_TRUNK` | `default`, `master` | Branch/revision cloned |
| `ZIDE.SSH_USERNAME` | `hari` | Clone username |
| `ZIDE.REPOSITORY_MODULE_DIR` | `zharehub` | Repository module directory name |
| `ZIDE.DOWNLOAD_URL` | `https://build.zohocorp.com/...zip` | Build archive download URL |
| `ZIDE.LOCAL_DOWNLOAD_URL` | `/path/to/local.zip` | Local build path (if used) |
| `ZIDE.PARENT_SERVICE` | `zharehub` | Parent service name (for dependencies) |
| `ZIDE.DEPLOYMENT_FOLDER` | `/Users/.../deployment/zharehub` | Absolute path to deployment |
| `ZIDE.DEPEND_SERVICES` | `dep1,dep2` | Comma-separated dependent service names |
| `ZIDE.RUNNABLE_SERVICES` | `ZOHOACCOUNTS` | Comma-separated runnable services |
| `ZIDE.SUBMODULES` | `sub1,sub2` | Comma-separated sub-module names |
| `ZIDE.SERVICE_KEY` | `ZHAREHUB` | Service key from zide_config.xml |
| `ZIDE.COLD_START` | `true` | Whether this is a fresh deployment |
| `ZIDE.DO_REPLACE` | `false` | Whether config replacement has been done |
| `ZIDE.PERMISSION` | `0` or `1` | 0=read-only, 1=read-write |
| `ZIDE.SOURCES` | `src/main/java` | Comma-separated source folder paths |
| `ZIDE.REPO_TYPE` | `1` or `2` | 1=Mercurial, 2=Git |
| `ZIDE.DEPLOY_TYPE` | `` or `M19` | Empty=standalone, M19=Tomcat WAR model |
| `ZIDE.MI_DEPLOYMENT` | `true`/`false` | Whether MI WAR was extracted |
| `ZIDE.TOMCAT_VERSION` | `9.0.65` | Auto-detected from catalina.jar |
| `ZIDE.PROJECT_JRE_HOME` | `/usr/lib/jvm/java-17` | JDK home path |

## zide_properties.xml Field Reference

Deployment properties stored per-service:

| Field | Example Value | Description |
|---|---|---|
| `ZIDE.HOST_NAME` | `hari-19343.csez.zohocorpin.com` | Machine hostname |
| `ZIDE.HTTP_PORT` | `8080` | HTTP port |
| `ZIDE.HTTPS_PORT` | `8443` | HTTPS port |
| `ZIDE.IAM_SERVER` | `https://accounts.csez.zohocorpin.com` | IAM server URL |
| `ZIDE.IAM_SERVICENAME` | `ZhareHub` | IAM service name |
| `ZIDE.USER_NAME` | `hari` | Developer username |
| `ZIDE.USER_EMAIL` | `hari@zohocorp.com` | Developer email |
| `ZIDE.MACHINE_IP` | `10.0.0.5` | Machine IP address |
| `ZIDE_DB_NAME` | `zharehub` | Database name |
| `ZIDE.SCHEMA_NAME` | `jbossdb` | Database schema name |

## zide_config.xml Service Definition Schema

Each service in the centralized `zide_config.xml` follows this structure:

```xml
<service key="SERVICE_KEY" isdeployable="true|false" name="DisplayName" moduledir="repo_dir">
  <deployment>
    <folder>path/in/archive</folder>
    <archivename>BuildArchive.zip</archivename>
  </deployment>
  <runnables>ZOHOACCOUNTS,OTHER_SERVICE</runnables>
  <project>
    <pathconfiguration>
      <libraryconfiguration>
        <libraries>
          <library name="LIB_NAME">
            <folder>relative/lib/path</folder>
          </library>
        </libraries>
        <classlibraries>
          <folder>relative/classpath/path</folder>
        </classlibraries>
        <jars>
          <folder>relative/jar/path</folder>
        </jars>
      </libraryconfiguration>
    </pathconfiguration>
    <auto-resourcecopy enabled="true">
      <copysets>
        <copy>
          <source>webapps/ROOT</source>
          <destination>webapps/ROOT</destination>
        </copy>
      </copysets>
    </auto-resourcecopy>
  </project>
  <configpage>
    <fields>uname_0,umail_0,dbname,url_0,label_0,iam_0,http_0,https_0,iamserv_0</fields>
  </configpage>
  <repository>
    <http>http://integ-build3/cgi-bin2/hgwebdir.cgi/</http>
    <ssh>ssh://{USER_NAME}@integ-build3//advent/hg/</ssh>
    <https>https://git.csez.zohocorpin.com/</https>
  </repository>
</service>
```

100+ services defined including: ZOHOACCOUNTS, ZOHOMAIL, ZOHONOTEBOOK, CRM, ZSECCERT, CODESEARCH, SHORTENURL, ZOHO_BAAS, and many more.

## Code Check Marker Types

| Marker Type ID | Check Class | Description |
|---|---|---|
| `com.zoho.zide.i18NMarker` | I18NCheck | Internationalization validation |
| `com.zoho.zide.pmdMarker` | PMDCheck | PMD static analysis |
| `com.zoho.zide.SecurityMarker` | SecurityCheck | Security patterns |
| `com.zoho.zide.DollarIDMarker` | DollarIDCheck | `$Id$` keyword |
| `com.zoho.zide.JSCheckMarker` | JSCheck | JavaScript validation |
| `com.zoho.zide.JsHintMarker` | JsHintCheck | JSHint linting |
| `com.zoho.zide.CtrlMMarker` | CtrlMCheck | Carriage return detection |
| `com.zoho.zide.JavaPackageMarker` | JavaPackageCheck | Package vs folder validation |
| `com.zoho.zide.ZohoKeywordMarker` | ZohoKeywordCheck | Prohibited keywords |
| `com.zoho.zide.PropStandardMarker` | PropertiesStandardCheck | .properties standards |
| `com.zoho.zide.SMCMarker` | SMCCheck | SMC compliance |
| `com.zoho.zide.HacksawMarker` | HacksawReportMarker | Security scan results |
| `com.zoho.zide.CustomCheckMarker` | CustomCheckMarker | User-defined checks |
| `com.zoho.zide.JUnitMarker` | JunitCheckMarker | JUnit test results |
| `com.zoho.zide.TPJSMarker` | (ThirdPartyJS) | Third-party JS detection |
| `com.zoho.zide.RepoMarker` | (Repository) | Repository restriction |

## Commit Check Configuration

`.zide/codecheck.conf` format:
```properties
# Comma-separated paths relative to repository root
i18n.excludes=
pmd.excludes=
dollarid.excludes=
```

Legal declaration template enforced in commit messages:
```
LEGAL DECLARATION:I hereby declare that the code submitted by me has not been copied 
from any third party source without due verification of license terms and does not 
infringe third party intellectual property rights. I also hereby declare that the code 
does not contain any offensive or abusive content.
LICENSE COMPATIBLE:YES
```

## Launch Configuration Properties

Properties from `Zide.properties` used by `LaunchUtil`:

| Key | Description |
|---|---|
| `launch.mainclass` | Java main class |
| `launch.vmarguments` | JVM arguments (-Xmx, -D flags, agent jars) |
| `launch.programarguments` | Program arguments |
| `launch.classpath` | Additional classpath entries |
| `launch.workingdirectory` | Working directory |
| `builder.antfile` | Ant build file path |
| `builder.targets` | Ant targets to run |
| `classpath.exclude.libraries` | Libraries to exclude from classpath |

## Config Replacer Variables

Variables substituted by `TextReplacer`/`XMLReplacer` during deployment setup:

| Variable | Source |
|---|---|
| `{HOSTNAME}` | `ZIDE.HOST_NAME` from zide_properties.xml |
| `{HTTP_PORT}` | `ZIDE.HTTP_PORT` |
| `{HTTPS_PORT}` | `ZIDE.HTTPS_PORT` |
| `{IAM_URL}` | `ZIDE.IAM_SERVER` |
| `{IAM_SERVICENAME}` | `ZIDE.IAM_SERVICENAME` |
| `{DB_NAME}` | `ZIDE_DB_NAME` |
| `{SCHEMA_NAME}` | `ZIDE.SCHEMA_NAME` |
| `{USER_NAME}` | `ZIDE.USER_NAME` |
| `{USER_EMAIL}` | `ZIDE.USER_EMAIL` |
| `{MACHINE_IP}` | `ZIDE.MACHINE_IP` |

## Standard .gitignore / .hgignore Entries

Patterns added by Eclipse ZIDE during project creation:

**.gitignore** (written to `.git/info/exclude`):
```
.zide_resources/
deployment/
*.class
.classpath
.project
.settings/
bin/
```

**.hgignore** (with `re:` prefix for regexp syntax):
```
syntax: regexp
re:.zide_resources
re:deployment/
re:\.classpath
re:\.project
re:\.settings
re:bin/
```

Plus module-specific patterns from `ZidePropertiesAPI.getModuleIgnoreEntries()`.

## ServiceConfig Inner Classes

`ServiceConfig` contains 5 inner configuration classes:

- **RepositoryConfig** -- clone URL prefixes (HTTP/SSH/HTTPS), module dir, username, password, trunk
- **DeploymentConfig** -- download URL, local URL, deployment folder path, isDeployable check
- **PathConfig** -- libraries (named user libraries), library JARs, class libraries; reads from `zide_config.xml` or `Zide.properties` depending on war model
- **LaunchConfig** -- VM args, main class, program args from `Zide.properties` `[launch]` section
- **BuilderConfig** -- Ant build targets and properties from `Zide.properties` `[builder]` section

## CMTools REST API

Base URL: `https://cmtools.csez.zohocorpin.com/api/v1`
Auth: `PRIVATE-TOKEN` header

| Endpoint | Purpose |
|---|---|
| `GET /products` | List all products |
| `GET /products?personal=true&include_role_acccess=true` | List user's products |

Response fields per product: `id`, `name`, `repository_url`, `download_url`, `service_name`

## Database Utilities

- `MysqlDBUtil` / `PostgreDBUtil` -- JDBC operations
- `ConfigDBUtil` -- queries module directories, MySQL credentials from central DB
- `ZideDBFactory` -- creates appropriate DB util
- RO mode: creates MySQL readonly user, updates grid configuration, sets `-Dapp.readonly.mode=true`
- SpyLog: P6Spy SQL logging toggle (`p6spy.jar`)

## Keyboard Shortcuts (Eclipse)

| Shortcut | Command |
|---|---|
| Ctrl+Alt+Shift+R | Run |
| Ctrl+Alt+Shift+D | Debug |
| Ctrl+Alt+Shift+S | Stop |
| Ctrl+Shift+Esc | Update Deployable Instances |

## Bundled Libraries

| Library | Version |
|---|---|
| mysql_connector.jar | (bundled) |
| postgresql-9.4-1201.jdbc41.jar | 9.4-1201 |
| commons-compress-1.0.jar | 1.0 |
| commons-io-2.11.0.jar | 2.11.0 |
| commons-lang3-3.12.0.jar | 3.12.0 |
| commons-text-1.10.0.jar | 1.10.0 |
| httpclient5-5.2.1.jar | 5.2.1 |
| httpcore5-5.2.1.jar | 5.2.1 |
| json.jar | (org.json) |
| slf4j-api-1.7.36.jar | 1.7.36 |
