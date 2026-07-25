---
name: zcp-appserver-setup
description: >-
  ZCP App. Server / appserver_container owns Tomcat SSL keystore (sas.keystore).
  ZIDE plugins (Eclipse, IntelliJ, VS Code) must never download or replace
  tomcat/conf/sas.keystore or invent HTTPS Connector injection. Use when working
  on DeploymentConfigPatcher, HTTPS, keystore, SSL, WRONG_VERSION_NUMBER, or
  App. Server container setup for Zoho local deployments.
---

# ZCP App. Server Setup — Keystore Ownership

## Rule (Eclipse-aligned)

The **App. Server / `appserver_container.zip`** ships whatever SSL material Tomcat needs (including `AdventNet/Sas/tomcat/conf/sas.keystore` when applicable).

**ZIDE plugins must not:**

- Download `sas.keystore` from apptier / ZCP static URLs
- Overwrite `{deployment}/AdventNet/Sas/tomcat/conf/sas.keystore`
- Inject an HTTPS `<Connector>` into `server.xml` as invented plugin behavior

Eclipse ZIDE (`com.zoho.zide`) only stores `ZIDE.HTTPS_PORT` (default `8443`) as a property. It does not download keystores or patch SSL connectors.

## IntelliJ plugin (`com.zoho.dzide`)

- [`DeploymentConfigPatcher`](src/main/kotlin/com/zoho/dzide/zide/DeploymentConfigPatcher.kt) patches Context, HTTP port, web.xml, persistence, security, and DB `configuration.properties` only
- Config replace is gated by `ZIDE.DO_REPLACE` / `replacerEveryStart` settings
- Product `install.xml` / `install.properties` may define HTTPS rules; only then may the data-driven Replacer apply them

## `WRONG_VERSION_NUMBER` / `EPROTO`

This usually means a TLS client hit a plain-HTTP listener (scheme/port mismatch). Do **not** “fix” it by inventing keystore download in the plugin. Verify the URL scheme, port, and that the container already provides working SSL if HTTPS is required.

## Related

- Eclipse reference: no `sas.keystore` / `SSLEnabled` in decompiled ZIDE sources
- Skip-download finding: container keystore often SHA512-matches ZCP static keystore — still leave the container file alone
