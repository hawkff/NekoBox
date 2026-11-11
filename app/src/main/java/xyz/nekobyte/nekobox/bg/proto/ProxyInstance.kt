package xyz.nekobyte.nekobox.bg.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import libcore.PortListener
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.ServiceNotification
import xyz.nekobyte.nekobox.bg.runRequiredCompletion
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) : BoxInstance(profile) {

    internal val tailscaleReadiness = TailscaleReadinessIntent()

    var lastSelectorGroupId = -1L
    var lastAutoSelect = false
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    // The socket this start listens with on the local port of its config, for the app's own requests.
    @Volatile
    var portListener: PortListener? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        lastAutoSelect = super.config.autoSelect
    }

    override suspend fun init() {
        super.init()
        Logs.d(safeConfigDiagnostics(config, pluginConfigs.size))
    }

    override fun launch() {
        box.setAsMain()
        // The core keeps the server a group was last switched to as it ran, and would start with that one again.
        // This start is for the profile it was given, which its config names: what the core kept goes first, so
        // no connection is ever taken through the other server.
        if (config.selectorGroupId >= 0L && !config.autoSelect) box.forgetSelected()
        super.launch() // start box
        portListener = config.localProxy?.let { local ->
            // Without it the service reports no local port, and the app's own requests go out without the proxy.
            Libcore.findPortListener(local.port).also { if (it == null) Logs.w("No listener of this start was found on local port ${local.port}") }
        }
        // ponytail: fixed cap; make it a setting if anyone needs a longer history.
        box.setConnectionHistory(if (DataStore.connectionDiagnostics) 300 else 0)
        // Assign the looper synchronously so close() always observes it (no
        // launch/close race). GlobalScope matches the previous scope semantics:
        // runOnDefaultDispatcher was GlobalScope.launch(Dispatchers.Default), and
        // TrafficLooper.start() launches its own loop on this scope.
        looper = service?.let { TrafficLooper(it.data, GlobalScope) }
        looper?.start()
        // The address the core dials for the selected server, for its row's country on Home; it
        // looks up only the server the connection uses, which the core resolves anyway.
        runOnDefaultDispatcher { lookUpServer() }
    }

    suspend fun closeAndPersist() = runRequiredCompletion(
        after = {
            try {
                looper?.stop()
            } finally {
                looper = null
                tailscaleReadiness.clear()
            }
        },
    ) {
        super.close()
    }

    // Synchronous compatibility path for Closeable callers. Service teardown uses
    // closeAndPersist() through runServiceTeardown instead of blocking its caller.
    override fun close() = runBlocking(Dispatchers.Default) {
        closeAndPersist()
    }
}
