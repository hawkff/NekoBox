package xyz.nekobyte.nekobox.aidl;

import xyz.nekobyte.nekobox.aidl.INekoBoxServiceCallback;

interface INekoBoxService {
  int getState();
  String getProfileName();

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
}
