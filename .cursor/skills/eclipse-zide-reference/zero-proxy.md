# ZeroTrust (Zero Agent) proxy

User-facing source: [Zero proxy configuration](https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/zero-proxy-configuration) on Zoho Learn (requires Zoho sign-in). The behavior below is from Eclipse ZIDE 2.4.7 decompiled source. If the article says otherwise, follow the article.

## Zero Agent status

The Zero Agent (Zero helper) is a local proxy at `127.0.0.1:3128`. ZIDE treats it as on when:

| OS | Check |
|---|---|
| macOS | `ZERO_HELPER_STATUS=ON` appears in `~/.zshrc`, else `~/.bash_profile`, else `~/.bashrc` (Eclipse reads only the first file that exists) |
| Linux | same string in `~/.bashrc` |
| Windows | environment variable `ZERO_HELPER_STATUS` equals `ON` (case-insensitive) |

Eclipse: `SystemUtil.getZeroAgentStatus()`.

## Eclipse plugin startup

`PluginStarter.earlyStartup()`:

1. Read workspace preference `zide.pref.revert_manual_network_proxies` ("Revert Manual Network Proxies / On Eclipse Startup" on the Zide preference page, default `true`).
2. If true:
   - Append bypass hosts to the Manual provider: `cm-server.csez.zohocorpin.com`, `cmsuite.csez.zohocorpin.com`, `cmtools.csez.zohocorpin.com`, `build.zohocorp.com`, `build-new.zohocorp.com`, `git.csez.zohocorpin.com`, `zide`, `zide.csez.zohocorpin.com`.
   - Unless `-Dzide.unalterproxy=true`: set active provider to `Direct` and unset `http_proxy` / `https_proxy` in the Eclipse process.
   - If both variables are still set, show "HTTP Proxy Warning": "Please unset HTTP_PROXY from your environment and restart Eclipse. Some Features of ZIDE may not work properly."
3. If false: log "Manual proxies are retained" and leave proxies alone.

`addZeroAgentProxy()` (Manual provider → `127.0.0.1:3128`) and `addZeroAgentProxyBypassHosts()` (`localhost,127.0.0.1`) exist but are never called in 2.4.7.

The bypass list is written onto the Manual provider after the active provider becomes Direct, so it has no effect.

## Eclipse Tomcat launch

`ZohoTomcatConfigurationDelegate.launch()`: when `getZeroAgentStatus()` is true, `appendProxyVMArgs()` adds `launch.proxy.vmargs` from the module `Zide.properties` (`deployment/{moduleDir}/M19/Zide.properties`), falling back to the common `deployment/zide/Zide.properties`:

```
launch.proxy.vmargs=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128
```

## IntelliJ plugin (com.zoho.dzide)

The IntelliJ plugin uses one proxy for the IDE, child tools, and Tomcat while Zero Agent is on. Eclipse only proxies Tomcat, which leaves CMTool, git, and wget off the agent.

- `util/ZeroProxy.kt` — status detection, host/port from `launch.proxy.vmargs` (module, then common `Zide.properties`, then `127.0.0.1:3128`), bypass `localhost,127.0.0.1`.
- Startup (`DzidePlugin`): when on, set IntelliJ HTTP proxy to manual host/port with those exceptions. The previous IntelliJ proxy is saved and restored when a later start finds the agent off. `-Dzide.unalterproxy=true` skips IDE proxy changes.
- Child processes (git, wget, Ant): `http_proxy`, `https_proxy`, `no_proxy` (and uppercase) in the environment.
- Tomcat: `launch.proxy.vmargs` appended to `CATALINA_OPTS` once; skipped when `-Dhttp.proxyHost` is already present.
- Settings → Tools → Zide: "Use ZeroTrust proxy" (default on). Off means no changes even when the agent is on.
- Shell rc files are never edited.
