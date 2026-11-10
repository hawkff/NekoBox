package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import android.os.Binder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxServiceCallback
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.Logs
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The traffic totals of profiles as the service keeps them while it runs: what it stores, and what becomes of a clear
 * from a screen, which sets the rows to zero on its own and then tells the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrafficLooperTest {
    private lateinit var service: BaseService.Interface
    private lateinit var data: BaseService.Data
    private lateinit var looper: TrafficLooper
    private lateinit var used: ProxyEntity
    private lateinit var idle: ProxyEntity
    private var group = 0L

    // What the lists are sent.
    private val sent = CopyOnWriteArrayList<List<TrafficData>>()

    // The default sink writes through the native core, which JVM tests do not load.
    private val originalLogSink = Logs.sink

    @Before
    fun setUp() {
        Logs.sink = {}
        ConfigBuilderTestEnv.reset()
        lateinit var created: BaseService.Data
        service = Proxy.newProxyInstance(BaseService.Interface::class.java.classLoader, arrayOf(BaseService.Interface::class.java)) { _, method, _ ->
            if (method.name == "getData") created else error("Unexpected service operation: ${method.name}")
        } as BaseService.Interface
        created = BaseService.Data(service)
        data = created
        val binder = Binder()
        val lists = Proxy.newProxyInstance(INekoBoxServiceCallback::class.java.classLoader, arrayOf(INekoBoxServiceCallback::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder

                "cbTrafficUpdateList" -> {
                    sent += (args!![0] as List<*>).filterIsInstance<TrafficData>()
                    null
                }

                else -> null
            }
        } as INekoBoxServiceCallback
        data.binder.registerCallback(lists, NekoBoxConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        ConfigBuilderTestEnv.io {
            group = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Servers"))
            used = add("used", 1_000, 2_000)
            idle = add("idle", 30, 40)
        }
    }

    @After
    fun tearDown() {
        data.binder.close()
        Logs.sink = originalLogSink
    }

    // A profile with totals from earlier sessions, as a config that is built now reads it.
    private fun add(name: String, rx: Long, tx: Long): ProxyEntity {
        val bean = SOCKSBean().apply {
            this.name = name
            initializeDefaultValues()
        }
        return ProxyEntity(groupId = group).putBean(bean).apply {
            id = ProfileDatabase.proxyDao.addProxy(this)
            ProfileDatabase.proxyDao.updateTraffic(id, rx, tx)
            this.rx = rx
            this.tx = tx
        }
    }

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    // The service starts to count, for a config that runs both profiles. Its loop asks the core for traffic; here
    // the test reports it instead.
    private suspend fun count() {
        looper = TrafficLooper(data, CoroutineScope(Dispatchers.IO))
        data.proxy = ProxyInstance(used, service).also { it.looper = looper }
        val config = ConfigBuildResult("", emptyList(), used.id, mapOf("used" to listOf(used), "idle" to listOf(idle)), mapOf(used.id to "used", idle.id to "idle"), -1L)
        data.traffic.withLock { looper.begin(config) }
    }

    private fun carried(profile: ProxyEntity, rx: Long, tx: Long) {
        val item = looper.idMap.getValue(profile.id)
        item.rx += rx
        item.tx += tx
    }

    // What the lists are sent when the group's totals are cleared.
    private fun zeros() = setOf(TrafficData(id = used.id), TrafficData(id = idle.id))

    // A profile's row: its totals, then its lifetime totals.
    private fun stored(profile: ProxyEntity) = ProfileDatabase.proxyDao.getById(profile.id)!!.let { listOf(it.rx, it.tx, it.lifetimeRx, it.lifetimeTx) }

    // The config reads the totals when it is built and the service counts later, so a clear can come in between.
    @Test
    fun countingStartsFromTheStoredTotals_notFromTheOnesTheConfigRead() = test {
        ProfileDatabase.proxyDao.clearTraffic(group)
        data.clearTraffic(group)
        count()
        carried(used, 5, 7)

        looper.stop()

        assertEquals(listOf(5L, 7L, 5L, 7L), stored(used))
        assertEquals(listOf(0L, 0L, 0L, 0L), stored(idle))
    }

    @Test
    fun totalsClearedWhileTheServiceCounts_stayCleared() = test {
        count()
        carried(used, 50, 80)
        ProfileDatabase.proxyDao.clearTraffic(group)
        data.clearTraffic(group)
        assertEquals(listOf(zeros()), sent.map { it.toSet() })
        carried(used, 7, 9)

        looper.stop()

        // Only what came after the clear, while the lifetime totals have all of the session.
        assertEquals(listOf(7L, 9L, 57L, 89L), stored(used))
        assertEquals(listOf(0L, 0L, 0L, 0L), stored(idle))
        // The profile that carried nothing is not stored again, nor sent.
        assertEquals(listOf(TrafficData(id = used.id, rx = 7, tx = 9)), sent.last())
    }

    // A profile used for the first time has nothing stored, so the screen finds no total of its own to take back from
    // its lists. They show what the service sent them, here its last totals, stored just before the screen's clear.
    @Test
    fun totalsTheListsOnlyHaveFromTheService_areTakenBackByItsZeros() = test {
        ProfileDatabase.proxyDao.clearTraffic(group)
        count()
        carried(used, 50, 80)
        looper.stop()
        assertEquals(listOf(TrafficData(id = used.id, rx = 50, tx = 80)), sent.last())
        ProfileDatabase.proxyDao.clearTraffic(group)

        data.clearTraffic(group)

        assertEquals(zeros(), sent.last().toSet())
        assertEquals(listOf(0L, 0L, 50L, 80L), stored(used))
    }

    // A store can land between the screen's clear and the moment the service hears of it: at shutdown, or at a stop.
    @Test
    fun totalsStoredAfterTheScreensClear_areClearedAgain_andTheListsAreTold() = test {
        count()
        carried(used, 50, 80)
        ProfileDatabase.proxyDao.clearTraffic(group)
        looper.persist()
        assertEquals(listOf(1_050L, 2_080L, 50L, 80L), stored(used))

        data.clearTraffic(group)

        assertEquals(listOf(0L, 0L, 50L, 80L), stored(used))
        // After the totals that store sent, the zeros.
        assertEquals(zeros(), sent.last().toSet())
        carried(used, 7, 9)
        looper.stop()
        assertEquals(listOf(7L, 9L, 57L, 89L), stored(used))
    }

    // A store at shutdown that was on its way when the service stopped comes after the last one.
    @Test
    fun afterTheLastStore_nothingIsStored_soALaterClearStands() = test {
        count()
        carried(used, 50, 80)
        looper.stop()
        // As the service does once it has stopped.
        data.proxy = null
        assertEquals(listOf(1_050L, 2_080L, 50L, 80L), stored(used))
        ProfileDatabase.proxyDao.clearTraffic(group)
        data.clearTraffic(group)

        looper.persist()

        assertEquals(listOf(0L, 0L, 50L, 80L), stored(used))
    }
}
