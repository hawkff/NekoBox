# What leaves the device

This page lists what network traffic NekoBox for Android lets through in each state, in the
order a connection goes through them. It covers the VPN mode, the proxy mode and the traffic of
the app itself.

## VPN mode

### Stopped

NekoBox filters nothing. Apps use the network directly.

Android can block that: with NekoBox set as the Always-on VPN and "Block connections without VPN"
on, Android drops all traffic outside the VPN, also while NekoBox is stopped or after it crashes.
Settings, Leak protection shows both Android settings.

### Connecting

With the kill switch on (the default for new installs), NekoBox opens a VPN interface that nothing
reads before the core starts. Traffic of the apps it routes waits there and goes nowhere. The core's
own connections, such as to the proxy server or the lookup of its address, use protected sockets
and leave through the physical network.

With the kill switch off, apps keep using the network directly until the core has started.

### Connected

Traffic of the routed apps goes to the core, and the routing rules send each connection through the
proxy, directly or nowhere. A direct connection leaves from this device's own address.

- IPv4: the interface routes all addresses, or only public ones with "Bypass LAN".
- IPv6: the interface routes IPv6 when "IPv6 route" is not "Disable". With "Disable", the default,
  it has no IPv6 address or route, and Android blocks IPv6 for the routed apps instead of sending it
  around the VPN.
- DNS: the interface's DNS server is the core. It resolves through the remote DNS server over the
  proxy, or through the direct DNS server for domains the rules send directly.
- Apps: per-app routing either leaves out the listed apps or routes only them. NekoBox itself always
  stays inside the VPN.
- LAN: with "Bypass LAN", private IPv4 ranges and IPv6 outside 2000::/3 reach the local network
  directly.
- HTTP proxy: with "Append HTTP proxy to VPN" (Android 10 and later), apps that read the system proxy
  send HTTP to the core's local proxy.
- WebRTC: with WebRTC leak protection, the core blocks STUN and TURN, so websites cannot learn the
  device's address that way. Voice and video calls may fail.

### Restarting

A reload or restart keeps the interface up when the kill switch is on, so the routed apps see a
pause, not a gap. With the kill switch off, traffic goes directly for the moment the core is down.

### Failed

With the kill switch on, a core that fails to start or stops on its own leaves the interface up with
nothing behind it. The notification reads "Kill switch: traffic blocked", and NekoBox tries again
after 5 seconds, doubling the wait up to a minute, until it connects or the user stops it.

With the kill switch off, the service stops and apps use the network directly.

### Stopped by the user

The interface closes and apps use the network directly, unless Android blocks connections without
VPN.

## Proxy mode

NekoBox opens no VPN interface. Only apps set to use the local proxy at 127.0.0.1:2080 (SOCKS5 and
HTTP on one port) go through the core. All other traffic, including DNS lookups of other apps, uses
the network directly. The kill switch does not apply.

With "Share connection" on, the local proxy also listens on every IPv4 network of the device, such
as Wi-Fi and a hotspot, and asks for the shared credentials.

## Traffic of the app itself

- Subscription updates and rule asset downloads go through the VPN while it is connected, and
  directly otherwise. A subscription can be set to update only while connected.
- URL and ping tests run their own core for each test and reach the tested server directly through
  protected sockets, so a test measures that server and not the active connection.
- A Tailscale node reaches its control server and relays through its own detour, or directly
  without one.
- Reports, only after the user agreed to send them: they leave only while a VPN carries the
  device's traffic. Until then, analytics events wait in a queue and error reports wait in memory,
  and they are dropped when the app process ends. Servers, profile names, keys, traffic and logs are
  never part of them.
- Problem reports leave only when the user shares one, after reading it. Servers, profile and group
  names, keys, UUIDs, IP addresses and host names are replaced first.
