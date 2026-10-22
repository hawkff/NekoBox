package xyz.nekobyte.nekobox.bg

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.aidl.INekoBoxServiceCallback
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NekoBoxConnectionTailscaleTest {
    // A remote binder whose process can die; it keeps the death recipients linked to it.
    private class DyingBinder : Binder() {
        var alive = true
        val recipients = mutableListOf<IBinder.DeathRecipient>()
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            recipients += recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = recipients.remove(recipient)
    }

    private class Service(val binder: Binder = Binder()) {
        lateinit var callback: INekoBoxServiceCallback
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = Proxy.newProxyInstance(INekoBoxService::class.java.classLoader, arrayOf(INekoBoxService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder

                "getState" -> BaseService.State.Stopped.ordinal

                "registerCallback" -> {
                    callback = args!![0] as INekoBoxServiceCallback
                    null
                }

                "unregisterCallback" -> null

                else -> {
                    calls += method.name to args.orEmpty().toList()
                    null
                }
            }
        } as INekoBoxService
        init {
            binder.attachInterface(api, "xyz.nekobyte.nekobox.aidl.INekoBoxService")
        }
    }

    @Test
    fun aDeadServiceProcessRebindsOnceWhicheverNoticeComesFirst() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (systemNoticeFirst in listOf(true, false)) {
                var binds = 0
                val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                        binds++
                        return true
                    }
                    override fun unbindService(connection: ServiceConnection) = Unit
                }
                val received = mutableListOf<BaseService.State>()
                val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, listenForDeath = true)
                val callback = object : NekoBoxConnection.Callback {
                    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                        received += state
                    }
                    override fun onServiceConnected(service: INekoBoxService) = Unit

                    // As the main screen does: drop the dead binding and bind again.
                    override fun onBinderDied() {
                        connection.disconnect(context)
                        connection.connect(context, this)
                    }
                }
                connection.connect(context, callback)
                try {
                    val dying = DyingBinder()
                    connection.onServiceConnected(null, Service(dying).binder)
                    dying.alive = false
                    if (systemNoticeFirst) {
                        // The system reports the lost connection before the death notice runs.
                        connection.onServiceDisconnected(null)
                        dying.recipients.toList().forEach { it.binderDied() }
                    } else {
                        dying.recipients.toList().forEach { it.binderDied() }
                        runCurrent()
                        connection.onServiceDisconnected(null)
                    }
                    runCurrent()
                    assertEquals("system notice first: $systemNoticeFirst", 2, binds)

                    // The service the new binding brings up still reaches the callback.
                    val replacement = Service()
                    connection.onServiceConnected(null, replacement.binder)
                    replacement.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
                    runCurrent()
                    assertEquals("system notice first: $systemNoticeFirst", listOf(BaseService.State.Connected), received)
                } finally {
                    connection.disconnect(context)
                }
            }
        } finally {
            DataStore.serviceState = BaseService.State.Idle
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stateUpdatesWithoutUiCallbackStillRequireConnectedService() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(context, null)
        try {
            val service = Service()
            connection.onServiceConnected(null, service.binder)
            service.callback.stateChanged(BaseService.State.Connecting.ordinal, null, null)
            assertEquals(BaseService.State.Stopped, DataStore.serviceState)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            service.callback.stateChanged(-1, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            service.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            connection.service = null
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            connection.service = service.api
        } finally {
            connection.disconnect(context)
            DataStore.serviceState = BaseService.State.Idle
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleStateCallbacksCannotChangeCacheOrUiAfterReconnect() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val received = mutableListOf<BaseService.State>()
        val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(
            context,
            object : NekoBoxConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                    assertEquals(state, DataStore.serviceState)
                    received += state
                }
                override fun onServiceConnected(service: INekoBoxService) = Unit
            },
        )
        try {
            val first = Service()
            connection.onServiceConnected(null, first.binder)
            first.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            connection.onServiceDisconnected(null)
            val second = Service()
            connection.onServiceConnected(null, second.binder)
            runCurrent()
            assertEquals(BaseService.State.Stopped, DataStore.serviceState)
            assertTrue(received.isEmpty())
            first.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            second.callback.stateChanged(BaseService.State.Connecting.ordinal, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            assertEquals(listOf(BaseService.State.Connecting), received)
            connection.disconnect(context)
            second.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            assertEquals(listOf(BaseService.State.Connecting), received)
        } finally {
            connection.disconnect(context)
            DataStore.serviceState = BaseService.State.Idle
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateCallbacksAreDroppedInsideMainDeliveryAndReconnectDoesNotReplay() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val received = mutableListOf<String>()
        val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(
            context,
            object : NekoBoxConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                override fun onServiceConnected(service: INekoBoxService) = Unit
                override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {
                    received += json
                }
                override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {
                    received += json
                }
            },
        )
        try {
            val first = Service()
            connection.onServiceConnected(null, first.binder)
            connection.observeTailscale(1, 42, "identity")
            connection.startTailscaleCheck(1, 42, "identity")
            connection.setTailscaleExitNode(1, 2, "peer", "old")
            assertTrue(first.calls.all { it.second.first() === first.callback })
            first.callback.cbTailscaleStatus(1, 1, "old-status")
            first.callback.cbTailscaleResult(1, 2, "old-result")
            connection.onServiceDisconnected(null)
            val second = Service()
            connection.onServiceConnected(null, second.binder)
            runCurrent()
            assertTrue(received.isEmpty())
            assertTrue(second.calls.isEmpty())
            first.callback.cbTailscaleStatus(1, 2, "late-old-status")
            second.callback.cbTailscaleStatus(3, 1, "new-status")
            connection.pingTailscalePeer(3, 4, "peer", 1_000)
            connection.cancelTailscaleRequest(3, 4)
            connection.closeTailscaleSession(3)
            runCurrent()
            assertEquals(listOf("new-status"), received)
            assertTrue(second.calls.all { it.second.first() === second.callback })
            connection.binderDied()
            second.callback.cbTailscaleResult(3, 4, "after-death")
            runCurrent()
            assertEquals(listOf("new-status"), received)
        } finally {
            connection.disconnect(context)
            Dispatchers.resetMain()
        }
    }
}
