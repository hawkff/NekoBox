package xyz.nekobyte.nekobox.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import xyz.nekobyte.nekobox.NekoBox

class ProxyService :
    Service(),
    BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "NekoBoxProxyService"
    override fun createNotification(profileName: String): ServiceNotification = ServiceNotification(this, profileName, "service-proxy", true)

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = NekoBox.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nekobox:proxy")
            .apply { acquire() }
    }

    override fun onDestroy() {
        data.binder.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
