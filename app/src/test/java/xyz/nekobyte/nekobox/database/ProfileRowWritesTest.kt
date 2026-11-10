package xyz.nekobyte.nekobox.database

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

// A list on show and an open editor hold rows as they were read. The service adds traffic, tests store results and an
// update replaces servers meanwhile, so each stores only what it changed: a whole row written back would undo the rest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileRowWritesTest {
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

    // Fails any statement in [block] that sets one of [columns] of a profile's row, whatever values it sets: by
    // default its traffic, test result and settings.
    private inline fun <T> leavingTheRestOfTheRow(columns: String = "tx, rx, lifetimeRx, lifetimeTx, status, ping, socksBean", block: () -> T): T {
        val database = ProfileDatabase.instance.openHelper.writableDatabase
        database.execSQL(
            """
            CREATE TEMP TRIGGER rest_of_the_row
            BEFORE UPDATE OF $columns ON proxy_entities
            BEGIN
                SELECT RAISE(ABORT, 'the write set more of the row than it changed');
            END
            """.trimIndent(),
        )
        try {
            return block()
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS rest_of_the_row")
        }
    }

    @Test
    fun aNewOrder_keepsWhatWasStoredSinceTheListWasRead() = runTest {
        withContext(Dispatchers.IO) {
            val shown = listOf(add("first", 1), add("second", 2))
            // Meanwhile the service adds a session's traffic, and an update moves the first server.
            ProfileDatabase.proxyDao.addLifetimeTraffic(shown[0].id, 100, 200)
            ProfileDatabase.proxyDao.updateProxy(ProfileDatabase.proxyDao.getById(shown[0].id)!!.putBean(server("first", "198.51.100.7")))

            leavingTheRestOfTheRow { ProfileDatabase.proxyDao.updateOrders(mapOf(shown[0].id to 2L, shown[1].id to 1L)) }

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

            leavingTheRestOfTheRow { ProfileManager.deleteProfile(groupId, profiles[1].id) }

            val left = ProfileDatabase.proxyDao.getByGroup(groupId)
            assertEquals(listOf(profiles[0].id to 1L, profiles[2].id to 2L), left.map { it.id to it.userOrder })
            assertEquals(listOf("first", "third"), left.map { it.displayName() })
            assertEquals(7L to 9L, left[1].lifetimeRx to left[1].lifetimeTx)
        }
    }

    @Test
    fun anEditorsSave_keepsWhatWasStoredWhileItWasOpen() = runTest {
        withContext(Dispatchers.IO) {
            val opened = add("first", 1)
            ProfileDatabase.proxyDao.addLifetimeTraffic(opened.id, 100, 200)
            ProfileDatabase.proxyDao.updateTestResult(opened.id, 1, 42, null)

            val saved = ProfileDatabase.proxyDao.updateBean(opened.id, server("renamed", "198.51.100.7"))!!

            val stored = ProfileDatabase.proxyDao.getById(opened.id)!!
            assertEquals("renamed" to "198.51.100.7", stored.displayName() to stored.requireBean().serverAddress)
            assertEquals(listOf(100L, 200L, 1L, 42L), listOf(stored.lifetimeRx, stored.lifetimeTx, stored.status.toLong(), stored.ping.toLong()))
            // The lists are told about the row as it is stored.
            assertEquals(listOf(100L, 1L), listOf(saved.lifetimeRx, saved.status.toLong()))
            // A profile removed meanwhile is not put back.
            ProfileDatabase.proxyDao.deleteById(opened.id)
            assertNull(ProfileDatabase.proxyDao.updateBean(opened.id, server("renamed", "198.51.100.7")))
            assertNull(ProfileDatabase.proxyDao.getById(opened.id))
        }
    }

    @Test
    fun anEditorsMoveToAnotherGroup_storesTheGroupAlone() = runTest {
        withContext(Dispatchers.IO) {
            val moved = add("first", 1)
            val other = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "other"))
            ProfileDatabase.proxyDao.addLifetimeTraffic(moved.id, 100, 200)

            leavingTheRestOfTheRow { ProfileDatabase.proxyDao.updateGroup(moved.id, other) }

            val stored = ProfileDatabase.proxyDao.getById(moved.id)!!
            assertEquals(listOf(other, 100L, 200L), listOf(stored.groupId, stored.lifetimeRx, stored.lifetimeTx))
        }
    }

    // Clearing a group's traffic or its test results is one statement over the group's rows, not each row written back.
    @Test
    fun clearingAGroupsTraffic_leavesItsTestResultsAndTheOtherGroups() = runTest {
        withContext(Dispatchers.IO) {
            val cleared = add("first", 1)
            val other = ProxyEntity(groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "other")), userOrder = 1).putBean(server("elsewhere", "192.0.2.9")).apply {
                id = ProfileDatabase.proxyDao.addProxy(this)
            }
            for (profile in listOf(cleared, other)) {
                ProfileDatabase.proxyDao.updateTraffic(profile.id, 300, 400, 0)
                ProfileDatabase.proxyDao.addLifetimeTraffic(profile.id, 100, 200)
                ProfileDatabase.proxyDao.updateTestResult(profile.id, 1, 42, null)
            }

            leavingTheRestOfTheRow("lifetimeRx, lifetimeTx, status, ping, socksBean, userOrder, groupId") { ProfileManager.clearTraffic(groupId) }

            val stored = ProfileDatabase.proxyDao.getById(cleared.id)!!
            assertEquals(listOf(0L, 0L, 100L, 200L, 1L, 42L), listOf(stored.rx, stored.tx, stored.lifetimeRx, stored.lifetimeTx, stored.status.toLong(), stored.ping.toLong()))
            val untouched = ProfileDatabase.proxyDao.getById(other.id)!!
            assertEquals(300L to 400L, untouched.rx to untouched.tx)
            // The rows count the clear, so totals counted before it are not stored after it. The other group's do not.
            assertEquals(1L to 0L, stored.trafficResets to untouched.trafficResets)
            assertEquals(0, ProfileDatabase.proxyDao.updateTraffic(cleared.id, 300, 400, 0))
            assertEquals(1, ProfileDatabase.proxyDao.updateTraffic(cleared.id, 5, 6, 1))
        }
    }

    @Test
    fun clearingAGroupsTestResults_leavesItsTraffic() = runTest {
        withContext(Dispatchers.IO) {
            val tested = add("first", 1)
            val untested = add("second", 2)
            ProfileDatabase.proxyDao.updateTraffic(tested.id, 300, 400, 0)
            ProfileDatabase.proxyDao.updateTestResult(tested.id, 3, 0, "timeout")

            leavingTheRestOfTheRow("tx, rx, lifetimeRx, lifetimeTx, socksBean, userOrder, groupId") { ProfileManager.clearTestResults(groupId) }

            val stored = ProfileDatabase.proxyDao.getById(tested.id)!!
            assertEquals(listOf(0L, 0L, 300L, 400L), listOf(stored.status.toLong(), stored.ping.toLong(), stored.rx, stored.tx))
            assertNull(stored.error)
            assertEquals(0, ProfileDatabase.proxyDao.getById(untested.id)!!.status)
        }
    }
}
