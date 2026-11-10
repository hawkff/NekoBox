package xyz.nekobyte.nekobox.database

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

// A list on show holds rows as they were read. The service adds traffic and an update replaces servers while it shows,
// so a new order is stored alone: a whole row written back would undo what was stored since.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileOrderTest {
    private var groupId = 0L

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        groupId = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true)) }
    }

    private fun server(name: String, address: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = address
        serverPort = 1080
        initializeDefaultValues()
    }

    private fun add(name: String, order: Long) = ProxyEntity(groupId = groupId, userOrder = order).putBean(server(name, "192.0.2.$order")).apply {
        id = ProfileDatabase.proxyDao.addProxy(this)
    }

    // Fails any statement in [block] that sets more of a profile's row than its order, whatever values it sets.
    private inline fun <T> storingTheOrderAlone(block: () -> T): T {
        val database = ProfileDatabase.instance.openHelper.writableDatabase
        database.execSQL(
            """
            CREATE TEMP TRIGGER order_alone
            BEFORE UPDATE OF tx, rx, lifetimeRx, lifetimeTx, status, ping, socksBean ON proxy_entities
            BEGIN
                SELECT RAISE(ABORT, 'a reorder wrote more than the order');
            END
            """.trimIndent(),
        )
        try {
            return block()
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS order_alone")
        }
    }

    @Test
    fun aNewOrder_keepsWhatWasStoredSinceTheListWasRead() = runTest {
        withContext(Dispatchers.IO) {
            val shown = listOf(add("first", 1), add("second", 2))
            // Meanwhile the service adds a session's traffic, and an update moves the first server.
            ProfileDatabase.proxyDao.addLifetimeTraffic(shown[0].id, 100, 200)
            ProfileDatabase.proxyDao.updateProxy(ProfileDatabase.proxyDao.getById(shown[0].id)!!.putBean(server("first", "198.51.100.7")))

            storingTheOrderAlone { ProfileDatabase.proxyDao.updateOrders(mapOf(shown[0].id to 2L, shown[1].id to 1L)) }

            assertEquals(listOf(shown[1].id, shown[0].id), ProfileDatabase.proxyDao.getIdsByGroup(groupId))
            val stored = ProfileDatabase.proxyDao.getById(shown[0].id)!!
            assertEquals(100L to 200L, stored.lifetimeRx to stored.lifetimeTx)
            assertEquals("198.51.100.7", stored.requireBean().serverAddress)
        }
    }

    @Test
    fun aRemovedProfile_leavesNoGapInTheOrder_andTheOthersAsStored() = runTest {
        withContext(Dispatchers.IO) {
            val profiles = listOf(add("first", 1), add("second", 5), add("third", 9))
            ProfileDatabase.proxyDao.addLifetimeTraffic(profiles[2].id, 7, 9)

            storingTheOrderAlone { ProfileManager.deleteProfile(groupId, profiles[1].id) }

            val left = ProfileDatabase.proxyDao.getByGroup(groupId)
            assertEquals(listOf(profiles[0].id to 1L, profiles[2].id to 2L), left.map { it.id to it.userOrder })
            assertEquals(listOf("first", "third"), left.map { it.displayName() })
            assertEquals(7L to 9L, left[1].lifetimeRx to left[1].lifetimeTx)
        }
    }
}
