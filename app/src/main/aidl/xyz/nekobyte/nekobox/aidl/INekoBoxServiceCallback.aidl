package xyz.nekobyte.nekobox.aidl;

import xyz.nekobyte.nekobox.aidl.SpeedDisplayData;
import xyz.nekobyte.nekobox.aidl.TrafficData;

oneway interface INekoBoxServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficData stats);
  void cbTrafficUpdateList(in List<TrafficData> stats);
  void cbSelectorUpdate(long id);
  void cbTailscaleStatus(long sessionId, long sequence, String json);
  void cbTailscaleResult(long sessionId, long requestId, String json);
}
