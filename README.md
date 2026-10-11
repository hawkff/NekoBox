<div align="center">

# NekoBox for Android

![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-0A7BBB?style=flat-square)](LICENSE)

</div>

## Contents

- [Disclaimer](#disclaimer)
- [Download](#download)
- [Protocols](#protocols)
  - [Tailscale](#tailscale)
- [Subscriptions](#subscriptions)
  - [Provider metadata](#provider-metadata)
- [Routing](#routing)
  - [Routing profiles](#routing-profiles)
- [Leak protection](#leak-protection)
- [Automation, sharing and diagnostics](#automation-sharing-and-diagnostics)
- [Local control API](#local-control-api)
- [Usage and crash reporting](#usage-and-crash-reporting)
- [License](#license)

## Disclaimer

This project's sole purpose is technical research. It does not provide any network proxy service. Do not use it for anything that breaks local laws or regulations. You bear all risk that comes from using it. The author may change, update or remove any part of this project or its contents at any time without notice.

## Download

Get the APK for your device's processor from the [Releases page](https://github.com/hawkff/NekoBox/releases).

Do not install NekoBox from Google Play. A third party has controlled that listing since May 2024, and its builds are closed source.

## Protocols

| Group | Protocols |
| --- | --- |
| Standard | SOCKS 4, 4a and 5, HTTP, HTTPS, SSH |
| Shadowsocks and V2Ray | Shadowsocks, ShadowsocksR, VMess, VLESS, Trojan |
| TLS and multiplexing | AnyTLS, ShadowTLS, Snell 1 to 5 |
| QUIC | Hysteria 2, TUIC 5, Juicity |
| WireGuard | WireGuard, AmneziaWG, Tailscale |
| Bundled clients | NaïveProxy, Mieru |
| Custom | A sing-box outbound or a complete sing-box configuration, in a Custom Config profile |

The profile editors offer these transport and security options:

| Protocol | V2Ray transports | Reality | ECH | Obfuscation |
| --- | :---: | :---: | :---: | :---: |
| VMess | ✅ | ✅ | ✅ | ❌ |
| VLESS | ✅ | ✅ | ✅ | ❌ |
| Trojan | ✅ | ✅ | ✅ | ❌ |
| AnyTLS | ❌ | ✅ | ❌ | ❌ |
| Hysteria 2 | ❌ | ❌ | ✅ | ✅ |
| TUIC 5 | ❌ | ❌ | ❌ | ❌ |
| Juicity | ❌ | ❌ | ❌ | ❌ |

The V2Ray transports are TCP, mKCP, WebSocket, HTTP/2, QUIC, gRPC, HTTPUpgrade and XHTTP. ECH is Encrypted Client Hello. Hysteria 2 obfuscation uses Salamander or Gecko.

NekoBox discontinued support for Hysteria 1. It keeps your saved Hysteria 1 profiles, including those from subscriptions, so you can export them.

### Tailscale

A Tailscale profile joins a tailnet with an auth key. Without a key, the app asks you to sign in through the browser. Set Control URL to use Headscale or another coordination server.

Choose an exit node from the tailnet to send internet traffic through it. Without one, the profile reaches tailnet addresses and MagicDNS names but not the internet. Turn on Use tailnet subnet routes to reach subnets that other nodes advertise.

Open Tailscale status from the profile editor to see this node and its peers, ping a peer or change the exit node without restarting the VPN. With the service off, the screen can also check a saved profile or sign it in.

To put other proxies in front of Tailscale in a chain, turn on Always use DERP. Otherwise direct peer connections leave from the device and skip those proxies.

## Subscriptions

To add a subscription, open Group, create a group, set its type to Subscription and enter the link. To add single profiles, use Add profile in Configuration. It reads the clipboard, a file or a QR code.

The app reads these formats:

- Share links, one per line or Base64-encoded
- Clash and Mihomo configurations
- sing-box outbounds, imported as Custom Config profiles
- Shadowsocks and ShadowsocksR JSON
- Hysteria 2 client configurations
- WireGuard and AmneziaWG `.conf` files

The app imports share links from Xray panels such as 3x-ui as the panels generate them. These include `wireguard://` links, AmneziaVPN `vpn://` links, links with the `ech` parameter and VMess links with flattened XHTTP settings. It runs Shadowsocks over WebSocket through its built-in `v2ray-plugin`. Other Shadowsocks transports have no sing-box equivalent, so the app skips those links.

The app imports server profiles and drops the rest of the source, such as routing rules, DNS settings, inbounds and proxy groups. The import preview lists what it dropped and the entries it could not read.

Subscription format, in the group's settings, sets the format that the app asks for in its User-Agent header. Providers can ignore the request. The NekoBox default prefers Clash and Mihomo. You can ask for share links or sing-box outbounds instead, or choose Auto. Auto tries each format, keeps the one that gives the most profiles the app can import, and moves to another if that one stops working.

Updates keep a profile that the provider stopped listing if you selected it or a rule, chain or group uses it. If the provider switches formats, the app asks before an update replaces your profiles, because replaced profiles lose their traffic history and custom settings. Background updates keep the old profiles until you update by hand.

In the group's settings, you can add up to five HTTPS backup links. The app tries them in order if the main link fails.

Send device ID is off by default. Turn it on for providers that count devices, such as [3x-ui](https://github.com/MHSanaei/3x-ui), [Marzban](https://github.com/Gozargah/Marzban) and [Remnawave](https://github.com/remnawave/panel) panels. The app then sends `X-HWID`, a random ID for this install, along with `X-Device-OS`, `X-Ver-OS` and `X-Device-Model`. It sends these headers over HTTPS and leaves them out of plain HTTP requests.

### Provider metadata

The app reads these keys from response headers, or from `#key: value` lines at the top of the subscription. If both carry a key, the app uses the header. An update that omits a key clears its old value.

| Key | Effect |
| --- | --- |
| `profile-title` | Names the group. Plain text, or Base64 after a `base64:` prefix. |
| `subscription-userinfo` | Shows traffic use and the `expire=` date on the group. `expire=0` means no expiry. With notifications allowed, the app reminds you once a day in the last three days and once after expiry. |
| `support-url`, `profile-web-page-url` | Adds the link to the group's menu. Accepts HTTP and HTTPS links. |
| `announce` | Shows up to 200 characters on the group. Plain text, or Base64 after a `base64:` prefix. |
| `profile-update-interval` | Sets the update interval in hours and turns on automatic updates each time the provider changes the value. Between changes, your own setting stays. |
| `new-url`, `new-domain` | Proposes a new HTTPS link, or the current link on a new host. |
| `fallback-url` | Proposes an HTTPS backup link. |
| `routing` | Delivers a routing profile. See [Routing profiles](#routing-profiles). |

Proposed links appear in the group settings. The app starts using one after you accept it. From then on, updates send the subscription's credentials to that link, plus the device ID if you turned that on.

## Routing

Route rules, in the Route screen, control where traffic goes. A rule matches apps, domains, IP addresses, ports, TCP or UDP, the detected protocol, or a rule set in `.srs` or `.json` format. It sends matching traffic through the proxy or a profile you select, lets it bypass the proxy, or blocks it. To choose which apps use the VPN at all, open Settings > Route Settings > Apps VPN mode.

A domain or app rule can also send matching DNS queries through the rule's proxy, so the lookups leave through the same exit as the traffic. You can give it a custom resolver. This setting, DNS through this proxy, is off by default. Those queries do not fall back to another resolver if the proxy fails.

A Proxy Chain profile connects through several profiles in order. Add exit proxy, in the Configuration menu, builds a chain from the selected profile to an exit profile you pick, then selects it and reconnects. In a group, Automatic selection uses the member with the lowest test latency and switches if that member fails or a faster one recovers.

### Routing profiles

A routing profile saves your route rules, DNS settings and per-app VPN settings under one name. Manage them in Route > Routing profiles. You can export a profile to a file, or to the clipboard as an `sn://routing/` link that carries the export JSON in Base64.

Opening a link, or importing one from the clipboard or a file, asks for confirmation and adds the profile without switching to it. An import replaces any profile with the same name. If you replace the active profile, its new rules and settings take effect at once.

A subscription can deliver one routing profile through the `routing` key, or as a link on its own line in the body. The app stores that profile apart from yours and refreshes it on each update. It takes the rules and leaves your DNS, app and other settings alone. You decide whether to switch to it.

The app also reads Happ and INCY routing links in the form `<scheme>://routing/add/<base64>`. It turns their domain and IP lists into block, proxy and direct rules, and reports the settings it leaves out. Provider requests to turn routing off, or to switch to the delivered profile, have no effect.

## Leak protection

Settings > Leak protection has these controls:

- Kill switch keeps the VPN interface up while the proxy restarts or fails. Tunneled apps lose their connection instead of reaching your network outside the tunnel. Apps you excluded from the VPN keep direct access. Stop the service to release the block.
- Always-on VPN and Block connections without VPN open Android's own settings. They also cover the time when the app's process is not running, which the kill switch cannot do.
- WebRTC leak protection rejects STUN, and TURN on its standard ports, inside the tunnel, so websites cannot use WebRTC to find your addresses. Voice and video calls in tunneled apps may stop working.
- Simulate location, under GPS spoofing, gives apps the approximate location of the proxy's exit IP address, or coordinates you enter. It is off by default and needs this app set as the mock location app in Developer options. All apps see the simulated location, Maps and navigation included, so turn it off from its notification before you navigate. The app looks up the exit location through the proxy, at ipapi.co unless you change the location service.

These controls work in VPN mode. A running service does not prove that protection works, so run a connection test after each start or reconnect.

## Automation, sharing and diagnostics

Network automation, under Settings > Connection, acts when the device joins mobile data, Ethernet, any Wi-Fi network or a Wi-Fi network you name. A rule can connect with a profile, switch profiles or disconnect. Android hides Wi-Fi names from apps without the location permission, so rules for a named network need it.

Share connection, in the Configuration menu, lets other devices use your connection through an HTTP and SOCKS5 proxy with a generated username and password. The proxy listens on all of the device's IPv4 networks, Wi-Fi and hotspot included, so anyone there with the credentials can connect. Regenerate the credentials to lock out devices that hold the old ones.

The Configuration menu tests profiles by TCP ping, ICMP ping or URL test. A URL test checks that the server reaches the internet. Under Tools > Network, NAT behaviour discovery checks your NAT type over STUN.

Under Tools > Backup, you can save profiles, routing rules and settings to a file or to WebDAV, and restore them. Backups contain your server credentials, so keep them private.

Turn on Enable Clash API in Settings > Advanced to serve the Clash API at `127.0.0.1:9090`. The setting also adds the sing-box Dashboard screen, which shows the bundled Yacd-meta dashboard.

## Local control API

The local control API lets scripts on the device, or on a computer through ADB, operate the app over HTTP. It is off by default. Enable it under Settings > Advanced > Local control API, and it listens on `127.0.0.1:9091`. Its token grants administrative access to the app, including credential export and backup restore, so keep it private. [API.md](API.md) covers setup, the command list and the Python client.

## Usage and crash reporting

The Help to improve switch, under Settings > Advanced, turns PostHog and Sentry reporting on or off together. It is on by default. A build without PostHog or Sentry settings sends nothing to that service.

Reports leave out exception messages, profiles, credentials, traffic, logs, and account or hardware identifiers. Session replay, screenshots, performance tracing and native crash capture stay off.

Turning reporting off stops it in the app and VPN processes without restarting the VPN. A request already in flight may still arrive. The app deletes PostHog's event queue when you turn reporting off. It starts a new queue each time reporting starts, so it does not send events left from an earlier session. Sentry keeps reports in memory and loses unsent ones when the process exits. Crashes still go to the app's own log.

<details>
<summary>Reporting from your own build</summary>

Create a PostHog project and a Sentry project. Put their public ingestion settings in environment variables or in the `local.properties` file, which Git ignores:

```properties
POSTHOG_PROJECT_TOKEN=phc_example_project_token
POSTHOG_HOST=https://us.i.posthog.com
SENTRY_DSN=https://examplePublicKey@o0.ingest.us.sentry.io/0
```

Replace the examples with values from your project dashboards. EU PostHog projects use `https://eu.i.posthog.com`. Environment variables override `local.properties`. An empty token or DSN turns that service off. Rebuild the app after you change these values.

The build embeds these values in the APK, so use public ingestion keys. It rejects PostHog personal API keys, which start with `phx_`. Keep Sentry auth tokens out of these settings too. Release builds keep Java and Kotlin class names and line numbers, so Sentry shows readable stack traces without uploaded mapping files. Native Go and NDK crashes stay in Android's local logs.

</details>

## License

[GNU General Public License v3.0](LICENSE)
