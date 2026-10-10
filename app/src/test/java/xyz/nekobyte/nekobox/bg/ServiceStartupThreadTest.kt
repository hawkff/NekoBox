package xyz.nekobyte.nekobox.bg

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.Action
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.Logs
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceStartupThreadTest {
    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        // This test uses one process; Robolectric cannot connect Room's remote invalidation service.
        shadowOf(application).declareComponentUnbindable(
            ComponentName(application, "androidx.room.MultiInstanceInvalidationService"),
        )
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun startupReadsNotificationGroupOffMainThread() {
        val profileId = ConfigBuilderTestEnv.io {
            val groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Example group"))
            val bean = SOCKSBean().apply {
                name = "Example node"
                initializeDefaultValues()
            }
            DataStore.showGroupInNotification = true
            ProfileDatabase.proxyDao.addProxy(ProxyEntity(groupId = groupId).putBean(bean))
        }
        val controller = Robolectric.buildService(TitleService::class.java).create()
        val service = controller.get()
        val originalLogSink = Logs.sink
        Logs.sink = {}
        try {
            service.onStartCommand(Intent().putExtra(Action.EXTRA_PROFILE_ID, profileId), 0, 1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (service.failure == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("[Example group] Example node", service.title)
        } finally {
            service.data.connectingJob?.cancel()
            if (service.data.closeReceiverRegistered) service.unregisterReceiver(service.data.receiver)
            service.data.binder.close()
            controller.destroy()
            Logs.sink = originalLogSink
        }
    }

    class TitleService :
        Service(),
        BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "TitleService"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null

        @Volatile var title: String? = null

        @Volatile var failure: String? = null

        override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        override fun acquireWakeLock() = Unit
        override fun createNotification(profileName: String): ServiceNotification {
            title = profileName
            error("Stop before native initialization")
        }
        override fun stopRunner(restart: Boolean, msg: String?) {
            failure = msg
        }
    }
}
