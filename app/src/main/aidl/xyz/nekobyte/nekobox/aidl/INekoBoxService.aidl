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

  // Appended: methods keep their transaction numbers.
  // Connection test of a profile with WireGuard or AmneziaWG hops through its running outbound, a server keeping one
  // session per client key: for the path the caller was asked to test, by its fingerprint (ConfigBuildResult.paths of
  // the caller's prepared config). JSON {"result": ...}: "tested" with "latencyMs" and "answer" (answerUrl read
  // through that outbound, missing when it could not be read); "not_running" when the config has no path for the
  // profile; "other_settings" when it runs another; "stopping" when the core is not running; "no_handshake" when the
  // test timed out on a server of the path that completed no handshake; "failed" with "error".
  String testWireGuardProfile(long profileId, String fingerprint, String answerUrl);
}
