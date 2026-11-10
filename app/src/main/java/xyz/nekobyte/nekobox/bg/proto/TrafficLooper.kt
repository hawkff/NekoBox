package xyz.nekobyte.nekobox.bg.proto

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.bg.ServiceNotification
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.TAG_BYPASS
import xyz.nekobyte.nekobox.fmt.TAG_PROXY
import xyz.nekobyte.nekobox.ktx.Logs

internal fun trafficPollingInterval(notificationInterval: Int) = notificationInterval.takeIf { it > 0 }?.toLong() ?: 1_000L

class TrafficLooper(
    val data: BaseService.Data,
    private val sc: CoroutineScope,
) {

    private var job: Job? = null

    // What the running config's profiles counted, by profile and by tag. Read and changed only under data.traffic:
    // in a round of the loop, in a store and in a clear.
    internal val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    internal val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    // for display
    private val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

    // Selector switches arrive from the JNI selector callback (NativeInterface) on a separate
    // coroutine. Funnel them through this channel so the loop applies them in a round of its own.
    // CONFLATED: only the latest selected id matters; superseded switches are safe to drop.
    private val selectChannel = Channel<Long>(Channel.CONFLATED)

    // The last totals are stored. Read and set under data.traffic.
    private var stopped = false

    suspend fun stop() {
        // cancelAndJoin (not cancel): wait for the loop coroutine to actually finish before the
        // final flush.
        job?.cancelAndJoin()
        selectChannel.close()
        data.traffic.withLock {
            // finally traffic post
            store()
            stopped = true
        }
    }

    /**
     * Stores the totals now, for the service's persistStats() hook on ACTION_SHUTDOWN, so a hard shutdown doesn't
     * drop the last session's bytes. Safe to call more than once. After stop() it stores nothing: the totals are
     * stored by then, and a clear that came since has to stand.
     */
    suspend fun persist() = data.traffic.withLock { if (!stopped) store() }

    // Flushes the cumulative rx/tx of the profiles this session counted traffic for into the DB (ProxyEntity.tx/rx
    // via ProfileManager.updateTraffic) and broadcasts a final traffic update. The rows of the rest hold their totals
    // already. The caller holds data.traffic.
    private suspend fun store() {
        if (!DataStore.profileTrafficStatistics) return
        val traffic = ArrayList<TrafficData>()
        for ((id, item) in idMap) {
            if (item === itemBypass || !item.counted) continue
            ProfileManager.updateTraffic(id, item.rx, item.tx)
            flushLifetimeDelta(id, item)
            traffic.add(TrafficData(id = id, rx = item.rx, tx = item.tx))
        }
        data.binder.broadcast { b ->
            b.cbTrafficUpdateList(traffic)
        }
        Logs.d("finally traffic post done")
    }

    /**
     * Add this session's not-yet-persisted delta into the profile's lifetime columns, advancing
     * the flushed marker only AFTER the DB write completes so a failed/incomplete write does not
     * silently drop bytes. Idempotent: a second call before more traffic flows adds nothing, so a
     * repeated store / a selector switch never double-counts. The caller holds data.traffic, so no
     * two of them add the same delta. Gated by profileTrafficStatistics like its callers.
     */
    private suspend fun flushLifetimeDelta(id: Long, item: TrafficUpdater.TrafficLooperData) {
        val rxDelta = (item.rx - item.rxBase) - item.lifetimeFlushedRx
        val txDelta = (item.tx - item.txBase) - item.lifetimeFlushedTx
        val rxAdd = if (rxDelta > 0) rxDelta else 0
        val txAdd = if (txDelta > 0) txDelta else 0
        if (rxAdd == 0L && txAdd == 0L) return
        ProfileManager.addLifetimeTraffic(id, rxAdd, txAdd)
        item.lifetimeFlushedRx += rxAdd
        item.lifetimeFlushedTx += txAdd
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    // Non-blocking entry for the JNI selector callback (NativeInterface). Posts the request;
    // it is applied on the loop coroutine via applySelect(). Never touches the maps directly.
    fun selectMain(id: Long) {
        selectChannel.trySend(id)
    }

    /** A screen cleared the totals of these profiles: they count from zero again. The caller holds data.traffic. */
    fun cleared(ids: List<Long>) {
        for (id in ids) idMap[id]?.clear()
    }

    /**
     * The config's profiles start counting, from the totals their rows hold now: the config read them when it was
     * built, and a screen may have cleared them since. Returns the tags the core is to count. The caller holds
     * data.traffic.
     */
    internal suspend fun begin(config: ConfigBuildResult): Set<String> {
        idMap.clear()
        idMap[-1] = itemBypass
        val stored = ProfileDatabase.proxyDao.getTraffic().associateBy { it.id }
        val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
        tags += config.trafficMap.keys

        fun count(id: Long, tag: String, ignore: Boolean) {
            val now = stored[id]
            val item = TrafficUpdater.TrafficLooperData(
                tag = tag,
                rx = now?.rx ?: 0,
                tx = now?.tx ?: 0,
                rxBase = now?.rx ?: 0,
                txBase = now?.tx ?: 0,
                ignore = ignore,
            )
            idMap[id] = item
            // The last one listed stays: the profile the tag belongs to.
            tagMap[tag] = item
            Logs.d("traffic count $tag to $id")
        }
        if (config.selectorGroupId >= 0L) {
            // In a group that picks its server only the picked profile counts, under the group's own tag. Each
            // profile the config can pick has one item, known by the tag the config gave that profile alone; the
            // hops of a path share tags and have none.
            config.profileTagMap.forEach { (id, tag) -> count(id, tag, ignore = true) }
            applySelect(config.mainEntId)
        } else {
            config.trafficMap.forEach { (tag, ents) -> for (ent in ents) count(ent.id, tag, ignore = false) }
        }
        return tags
    }

    // The caller holds data.traffic.
    private suspend fun applySelect(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // The profile switched away from stores its totals now, and the round waits for that: a write left to
            // finish on its own could land after a clear, or after the last totals were stored at a stop. This item
            // counts for that profile alone, while its tag can list more of them, such as the hops of a chain.
            if (DataStore.profileTrafficStatistics) {
                ProfileManager.updateTraffic(selectorNowId, rx, tx)
                flushLifetimeDelta(selectorNowId, this)
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    // The "proxy" group picks members on its own (urltest failover, or a selector switched without
    // a restart), so poll its current pick each tick and move accounting, notification title
    // and the UI's selected profile along with it.
    private suspend fun followGroupSelection(proxy: ProxyInstance) {
        val nowTag = proxy.box.selectedOutbound()
        if (nowTag.isEmpty() || nowTag == selectorNowFakeTag) return
        val id = proxy.config.profileTagMap.entries.firstOrNull { it.value == nowTag }?.key ?: return
        if (id == selectorNowId) return
        val ent = ProfileManager.getProfile(id) ?: return
        applySelect(id)
        proxy.displayProfileName = ServiceNotification.genTitle(ent)
        data.notification?.postNotificationTitle(proxy.displayProfileName)
        data.binder.broadcast { it.cbSelectorUpdate(id) }
    }

    private suspend fun loop() {
        val notificationInterval = DataStore.speedInterval
        val delayMs = trafficPollingInterval(notificationInterval)
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics

        var trafficUpdater: TrafficUpdater? = null

        while (sc.isActive) {
            val proxy = data.proxy
            if (proxy == null || !proxy.isInitialized()) {
                delay(delayMs)
                continue
            }
            // Visible UI and the enabled local API consume live traffic samples.
            val listening = data.state == BaseService.State.Connected &&
                (
                    data.binder.callbackIdMap.containsValue(NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) ||
                        data.binder.callbackIdMap.containsValue(NekoBoxConnection.CONNECTION_ID_LOCAL_API)
                    )

            // One round of counting. A clear from a screen and a store at shutdown wait for it, and it for them. The
            // lists are told within the round, so its totals never reach them after the zeros of a later clear.
            val speed = data.traffic.withLock {
                val updater = trafficUpdater ?: run {
                    val tags = begin(proxy.config)
                    TrafficUpdater(
                        box = proxy.box,
                        items = idMap.values.toList(),
                    ).also {
                        trafficUpdater = it
                        proxy.box.setV2rayStats(tags.joinToString("\n"))
                    }
                }

                // Apply any selector switches posted from the JNI callback.
                while (true) {
                    val sel = selectChannel.tryReceive().getOrNull() ?: break
                    applySelect(sel)
                }
                if (proxy.config.selectorGroupId >= 0L) followGroupSelection(proxy)

                updater.updateAll()
                if (!sc.isActive) return

                // add all non-bypass to "main"
                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                tagMap.forEach { (_, it) ->
                    if (!it.ignore) {
                        mainTxRate += it.txRate
                        mainRxRate += it.rxRate
                    }
                    mainTx += it.tx - it.txBase
                    mainRx += it.rx - it.rxBase
                }

                // speed
                val speed = SpeedDisplayData(
                    mainTxRate,
                    mainRxRate,
                    if (showDirectSpeed) itemBypass.txRate else 0L,
                    if (showDirectSpeed) itemBypass.rxRate else 0L,
                    mainTx,
                    mainRx,
                )

                if (listening) {
                    // Only the profiles this session counted traffic for: a list shows the stored totals of the rest
                    // already, and a group of hundreds of servers has one or a few in use.
                    val batch = ArrayList<TrafficData>()
                    if (profileTrafficStatistics) {
                        idMap.forEach { (id, item) -> if (item.counted) batch.add(TrafficData(id = id, rx = item.rx, tx = item.tx)) }
                    }
                    data.binder.broadcast { b ->
                        val id = data.binder.callbackIdMap[b.asBinder()]
                        if (id == NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND || id == NekoBoxConnection.CONNECTION_ID_LOCAL_API) {
                            b.cbSpeedUpdate(speed)
                            if (batch.isNotEmpty()) b.cbTrafficUpdateList(batch)
                        }
                    }
                }
                speed
            }

            // ServiceNotification
            data.notification?.apply {
                if (notificationInterval > 0 && listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }
}
