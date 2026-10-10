package xyz.nekobyte.nekobox.ui

import android.content.Context
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection

internal interface TailscaleStatusTransport {
    interface Listener {
        fun connected(state: BaseService.State)
        fun disconnected()
        fun serviceState(state: BaseService.State)
        fun status(sessionId: Long, sequence: Long, json: String)
        fun result(sessionId: Long, requestId: Long, json: String)
    }
    fun connect(listener: Listener)
    fun disconnect()
    fun observe(sessionId: Long, profileId: Long, identity: String)
    fun start(sessionId: Long, profileId: Long, identity: String)
    fun close(sessionId: Long)
    fun ping(sessionId: Long, requestId: Long, peerId: String)
    fun selectExit(sessionId: Long, requestId: Long, peerId: String, expectedExit: String)
    fun cancel(sessionId: Long, requestId: Long)
}

internal class TailscaleStatusClient(private val context: Context) : TailscaleStatusTransport {
    private var connection: NekoBoxConnection? = null
    private var epoch = 0L

    override fun connect(listener: TailscaleStatusTransport.Listener) {
        if (connection != null) return
        val capturedEpoch = ++epoch
        val client = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_TAILSCALE_STATUS, listenForDeath = true)
        connection = client
        client.connect(
            context,
            object : NekoBoxConnection.Callback {
                override fun onServiceConnected(service: INekoBoxService) {
                    if (capturedEpoch == epoch) listener.connected(BaseService.State.values()[service.state])
                }
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                    if (capturedEpoch == epoch) listener.serviceState(state)
                }
                override fun onServiceDisconnected() {
                    if (capturedEpoch == epoch) listener.disconnected()
                }
                override fun onBinderDied() {
                    if (capturedEpoch == epoch) {
                        disconnect()
                        listener.disconnected()
                        connect(listener)
                    }
                }
                override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {
                    if (capturedEpoch == epoch) listener.status(sessionId, sequence, json)
                }
                override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {
                    if (capturedEpoch == epoch) listener.result(sessionId, requestId, json)
                }
            },
        )
    }

    override fun disconnect() {
        ++epoch
        connection?.disconnect(context)
        connection = null
    }
    private fun connected() = checkNotNull(connection?.takeIf { it.service != null })
    override fun observe(sessionId: Long, profileId: Long, identity: String) {
        connected().observeTailscale(sessionId, profileId, identity)
    }
    override fun start(sessionId: Long, profileId: Long, identity: String) {
        connected().startTailscaleCheck(sessionId, profileId, identity)
    }
    override fun close(sessionId: Long) {
        connected().closeTailscaleSession(sessionId)
    }
    override fun ping(sessionId: Long, requestId: Long, peerId: String) {
        connected().pingTailscalePeer(sessionId, requestId, peerId, 10_000)
    }
    override fun selectExit(sessionId: Long, requestId: Long, peerId: String, expectedExit: String) {
        connected().setTailscaleExitNode(sessionId, requestId, peerId, expectedExit)
    }
    override fun cancel(sessionId: Long, requestId: Long) {
        connected().cancelTailscaleRequest(sessionId, requestId)
    }
}
