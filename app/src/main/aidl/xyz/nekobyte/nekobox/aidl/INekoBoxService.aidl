package xyz.nekobyte.nekobox.aidl;

import xyz.nekobyte.nekobox.aidl.INekoBoxServiceCallback;
import xyz.nekobyte.nekobox.aidl.LocalProxy;
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData;

interface INekoBoxService {
  int getState();
  String getProfileName();
  // The profile the service runs: the one its group picked last when the group picks on its own; -1 while it runs
  // none.
  long getRunningProfileId();
  // The local mixed inbound of the connected service, for the app's own requests; null while
  // the service is not connected or runs a config without that inbound.
  @nullable LocalProxy getLocalProxy();
  // Another app's VPN took the place of this service's: its tunnel is no longer this app's, though
  // the service is still started while it stops.
  boolean getRevoked();
  // The traffic sample of the last polling round; null while the service is not connected.
  @nullable SpeedDisplayData getLatestSpeed();

  void registerCallback(in INekoBoxServiceCallback cb, int id);
  oneway void unregisterCallback(in INekoBoxServiceCallback cb);

  oneway void observeTailscale(in INekoBoxServiceCallback cb, long sessionId, long profileId, String expectedIdentity);
  oneway void startTailscaleCheck(in INekoBoxServiceCallback cb, long sessionId, long profileId, String expectedIdentity);
  oneway void closeTailscaleSession(in INekoBoxServiceCallback cb, long sessionId);
  oneway void pingTailscalePeer(in INekoBoxServiceCallback cb, long sessionId, long requestId, String peerId, int timeoutMs);
  oneway void setTailscaleExitNode(in INekoBoxServiceCallback cb, long sessionId, long requestId, String peerId, String expectedSavedSelection);
  oneway void cancelTailscaleRequest(in INekoBoxServiceCallback cb, long sessionId, long requestId);

  int urlTest();
  // Connection test through the running outbound of one profile in the current
  // configuration (a Tailscale node used as a hop counts); fails when it is not part of it.
  int urlTestProfile(long profileId);
  // Connection test of a saved profile with WireGuard or AmneziaWG hops through its running outbound, as JSON
  // {"latencyMs": int}. A server keeps one session per client key, so nothing else may test such a profile while
  // the service runs it. Fails with a reason the caller maps (BaseService.kt): not part of the configuration, a hop
  // of it opening a session the core opens, run with other settings than the saved ones, or a timeout on a server of
  // its path that completed no handshake.
  String testWireGuardProfile(long profileId);
  // Peers of a running Tailscale profile as JSON (name, dnsName, ips, online, exitNode).
  String tailscalePeers(long profileId);
  // Ids of the Tailscale profiles whose nodes this service runs, as a JSON array. A node
  // has one saved identity, so nobody else may start one of these while the service runs.
  String runningTailscaleProfiles();

  // Live routed connections as JSON (ConnectionEntry array); closed ones too when the
  // bounded history is enabled through connection diagnostics.
  String connections(boolean includeClosed);

  // Peer state of every running outbound of a WireGuard or AmneziaWG profile, as JSON:
  // {"running": bool, "instances": [{"owner": profileId, "peers": [...]} or {"owner": profileId, "error": text}]}.
  String wireguardStatus(long profileId);
}
