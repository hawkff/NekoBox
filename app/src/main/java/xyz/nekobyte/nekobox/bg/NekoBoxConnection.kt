package xyz.nekobyte.nekobox.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import xyz.nekobyte.nekobox.Action
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.aidl.INekoBoxServiceCallback
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher

class NekoBoxConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false,
) : ServiceConnection,
    IBinder.DeathRecipient {

    companion object {
        val serviceClass get() = serviceClassForMode(DataStore.serviceMode)

        private fun serviceClassForMode(mode: String) = when (mode) {
            Key.MODE_PROXY -> ProxyService::class
            Key.MODE_VPN -> VpnService::class
            else -> throw UnknownError()
        }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4
        const val CONNECTION_ID_SHARE_CONNECTION = 5
        const val CONNECTION_ID_TAILSCALE_SETTINGS = 6
        const val CONNECTION_ID_TAILSCALE_STATUS = 7
        const val CONNECTION_ID_WIREGUARD_STATUS = 8
        const val CONNECTION_ID_LOCAL_API = 9

        var restartingApp = false

        // How long a running service may take to answer. The answer arrives on the main thread.
        private const val SERVICE_ANSWER_MS = 1_500L

        // The services that were started, in whichever mode: the mode in the settings is that of the next start, and
        // after a change of mode the old service can still be stopping while the new one runs. A service that a
        // screen only holds on to was not started and runs nothing.
        @Suppress("DEPRECATION") // It still tells an app of its own services, and nothing else is asked.
        private fun startedServices(): List<ComponentName> {
            val services = setOf(ProxyService::class.java.name, VpnService::class.java.name)
            return NekoBox.activity.getRunningServices(Int.MAX_VALUE)
                .filter { it.started && it.service.packageName == NekoBox.application.packageName && it.service.className in services }
                .map { it.service }
        }

        /**
         * Asks the service that runs one thing and unbinds again; null when none runs, or none has an answer in time.
         * A process knows what the service does only while a connection of its own is bound, so code that also runs
         * without one asks here: the subscription job, and the app's own requests. Nothing is started to answer. The
         * answer arrives on the main thread, so that thread must not be blocked waiting for it.
         */
        suspend fun <T : Any> ask(context: Context, question: (INekoBoxService) -> T?): T? {
            for (service in startedServices()) ask(context, service, question)?.let { return it }
            return null
        }

        private suspend fun <T : Any> ask(context: Context, service: ComponentName, question: (INekoBoxService) -> T?): T? {
            val answer = CompletableDeferred<T?>()
            val asking = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    // Whatever the question throws goes to whoever asked, not to the main thread it is asked on.
                    try {
                        answer.complete(question(INekoBoxService.Stub.asInterface(binder)))
                    } catch (_: RemoteException) {
                        answer.complete(null)
                    } catch (e: Exception) {
                        answer.completeExceptionally(e)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            try {
                // Without BIND_AUTO_CREATE only a running service answers.
                if (!context.bindService(Intent(Action.SERVICE).setComponent(service), asking, 0)) return null
                return withTimeoutOrNull(SERVICE_ANSWER_MS) { answer.await() }
            } finally {
                try {
                    context.unbindService(asking)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    interface Callback {
        // smaller INekoBoxServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbTrafficUpdateList(data: List<TrafficData>) {
            data.forEach { cbTrafficUpdate(it) }
        }
        fun cbSelectorUpdate(id: Long) {}
        fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {}
        fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: INekoBoxService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    @Volatile var boundMode: String? = null
        private set

    private val followsDeath get() = listenForDeath || connectionId == CONNECTION_ID_TAILSCALE_STATUS
    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null

    @Volatile private var epoch = 0L
    private var serviceCallback = newServiceCallback(epoch)
    private fun newServiceCallback(callbackEpoch: Long) = object : INekoBoxServiceCallback.Stub() {
        private fun deliver(block: (Callback) -> Unit) {
            runOnMainDispatcher {
                if (epoch == callbackEpoch && service != null) callback?.let(block)
            }
        }

        override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) = deliver { it.cbTailscaleStatus(sessionId, sequence, json) }

        override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) = deliver { it.cbTailscaleResult(sessionId, requestId, json) }

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            runOnMainDispatcher {
                if (epoch != callbackEpoch || service == null) return@runOnMainDispatcher
                DataStore.serviceState = s
                callback?.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            deliver { it.cbSpeedUpdate(stats) }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            deliver { it.cbTrafficUpdate(stats) }
        }

        override fun cbTrafficUpdateList(stats: MutableList<TrafficData>) {
            deliver { it.cbTrafficUpdateList(stats) }
        }

        override fun cbSelectorUpdate(id: Long) {
            deliver { it.cbSelectorUpdate(id) }
        }

        override fun missingPlugin(profileName: String, pluginName: String) {
            deliver { it.missingPlugin(profileName, pluginName) }
        }
    }

    private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Volatile private var tailscaleBinding: Pair<INekoBoxService, INekoBoxServiceCallback>? = null

    var service: INekoBoxService? = null

    fun observeTailscale(sessionId: Long, profileId: Long, expectedIdentity: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.observeTailscale(binding.second, sessionId, profileId, expectedIdentity)
    }

    fun startTailscaleCheck(sessionId: Long, profileId: Long, expectedIdentity: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.startTailscaleCheck(binding.second, sessionId, profileId, expectedIdentity)
    }

    fun closeTailscaleSession(sessionId: Long) {
        val binding = tailscaleBinding ?: return
        binding.first.closeTailscaleSession(binding.second, sessionId)
    }

    fun pingTailscalePeer(sessionId: Long, requestId: Long, peerId: String, timeoutMs: Int) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.pingTailscalePeer(binding.second, sessionId, requestId, peerId, timeoutMs)
    }

    fun setTailscaleExitNode(sessionId: Long, requestId: Long, peerId: String, expectedSavedSelection: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.setTailscaleExitNode(binding.second, sessionId, requestId, peerId, expectedSavedSelection)
    }

    fun cancelTailscaleRequest(sessionId: Long, requestId: Long) {
        val binding = tailscaleBinding ?: return
        binding.first.cancelTailscaleRequest(binding.second, sessionId, requestId)
    }

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            service?.registerCallback(serviceCallback, id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        val mode = boundMode ?: return
        if (name != null && name.className != serviceClassForMode(mode).name) return
        epoch++
        serviceCallback = newServiceCallback(epoch)
        this.binder = binder
        val service = INekoBoxService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (followsDeath) {
                val connectedEpoch = epoch
                deathRecipient = IBinder.DeathRecipient { handleDeath(connectedEpoch, binder) }.also { binder.linkToDeath(it, 0) }
            }
            check(!callbackRegistered)
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
            tailscaleBinding = service to serviceCallback
            DataStore.serviceState = BaseService.State.values()[service.state]
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        callback?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        // The service's process died. The system may not bring the service back for this binding,
        // so a connection that follows deaths rebinds as for a death notice; whichever of the two
        // notices comes first handles the death.
        val died = followsDeath && binder?.isBinderAlive == false
        tailscaleBinding = null
        epoch++
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
        if (died && !restartingApp) callback?.onBinderDied()
    }

    override fun binderDied() = handleDeath(epoch, binder)

    private fun handleDeath(connectedEpoch: Long, deadBinder: IBinder?) {
        runOnMainDispatcher {
            if (epoch != connectedEpoch || binder !== deadBinder) return@runOnMainDispatcher
            tailscaleBinding = null
            epoch++
            service = null
            binder = null
            callbackRegistered = false
            if (!restartingApp) callback?.onBinderDied()
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) {
            try {
                service.unregisterCallback(serviceCallback)
            } catch (_: RemoteException) {
            }
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val mode = DataStore.serviceMode
        val intent = Intent(context, serviceClassForMode(mode)).setAction(Action.SERVICE)
        boundMode = mode
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        tailscaleBinding = null
        epoch++
        unregisterCallback()
        if (connectionActive) {
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
            } // ignore
        }
        connectionActive = false
        boundMode = null
        if (followsDeath) {
            try {
                deathRecipient?.let { binder?.unlinkToDeath(it, 0) }
            } catch (_: NoSuchElementException) {
            }
        }
        binder = null
        deathRecipient = null
        service = null
        callback = null
    }
}
