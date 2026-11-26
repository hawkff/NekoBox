package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.SpeedDisplayData
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.bg.BaseService.State
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ui.ConnectWidgets.ServiceAnswer

// What a placed widget shows, read from the views Robolectric's widget host applies the drawings to.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(AppWidgetManager.getInstance(context))
    private val logSink = Logs.sink
    private val ask = ConnectWidgets.askService
    private val bindWatch = ConnectWidgets.bindWatch
    private val unbindWatch = ConnectWidgets.unbindWatch
    private val service = ComponentName("xyz.nekobyte.nekobox.test", "Service")
    private var group = 0L

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        Logs.sink = {}
        ConnectWidgets.askService = { null }
        group = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Mullvad")) }
        ConfigBuilderTestEnv.io { profile(17, "Tokyo 004") }
        DataStore.selectedProxy = 17L
        ProfileManager.addListener(ConnectWidgets.modelListener)
        GroupManager.addListener(ConnectWidgets.modelListener)
    }

    @After
    fun tearDown() {
        drawn()
        // Lets go of a watched service: the watch outlives a test.
        ConnectWidgets.askService = { null }
        runBlocking { ConnectWidgets.refresh(context).join() }
        ConnectWidgets.bindWatch = bindWatch
        ConnectWidgets.unbindWatch = unbindWatch
        ConnectWidgets.askService = ask
        ProfileManager.removeListener(ConnectWidgets.modelListener)
        GroupManager.removeListener(ConnectWidgets.modelListener)
        Logs.sink = logSink
    }

    private fun profile(id: Long, name: String) = ProfileDatabase.proxyDao.addProxy(
        ProxyEntity(id = id, groupId = group).putBean(
            SOCKSBean().apply {
                initializeDefaultValues()
                this.name = name
            },
        ),
    )

    private fun drawn() = runBlocking { withTimeout(10_000) { ConnectWidgets.lastRender?.join() } }

    private fun place(): Int = manager.createWidget(ConnectWidget::class.java, R.layout.widget_connect).also { drawn() }

    private fun text(id: Int, view: Int) = manager.getViewFor(id).findViewById<TextView>(view).text.toString()

    private fun answer(state: State, running: Long = -1L) = ServiceAnswer(service, state, running, null)

    @Test
    fun anOlderDrawingNeverLandsAfterANewerOne() {
        val id = place()
        assertEquals("Not connected", text(id, R.id.widget_state))
        val answers = Channel<CompletableDeferred<ServiceAnswer?>>(Channel.UNLIMITED)
        ConnectWidgets.askService = { CompletableDeferred<ServiceAnswer?>().also { answers.send(it) }.await() }
        runBlocking {
            val older = ConnectWidgets.refresh(context)
            val first = withTimeout(5_000) { answers.receive() }
            // Asked while the older drawing waits for the service: the newer one waits its turn instead of asking too.
            val newer = ConnectWidgets.refresh(context)
            delay(300)
            assertTrue(answers.isEmpty)
            // The older drawing heard Connected; by the time the newer one asks, the service is gone.
            first.complete(answer(State.Connected))
            withTimeout(5_000) { answers.receive() }.complete(null)
            older.join()
            newer.join()
        }
        assertEquals("Not connected", text(id, R.id.widget_state))
    }

    @Test
    fun theProfileTheServiceRunsShowsByItsOwnNameAndGroup() {
        ConfigBuilderTestEnv.io { profile(18, "Osaka 005") }
        DataStore.showGroupInNotification = true
        val id = place()
        // The group picked another server than the one selected.
        ConnectWidgets.askService = { answer(State.Connected, running = 18L) }
        runBlocking { ConnectWidgets.refresh(context).join() }
        assertEquals("Osaka 005", text(id, R.id.widget_profile))
        assertEquals(" · Mullvad", text(id, R.id.widget_group))
        // Off, the selected profile.
        ConnectWidgets.askService = { null }
        runBlocking { ConnectWidgets.refresh(context).join() }
        assertEquals("Tokyo 004", text(id, R.id.widget_profile))
    }

    @Test
    fun aServiceWhoseProcessEndsUnseenIsShownOff() {
        val id = place()
        val bound = mutableListOf<ComponentName?>()
        ConnectWidgets.bindWatch = { _, intent ->
            bound += intent.component
            true
        }
        ConnectWidgets.unbindWatch = { bound += null }
        ConnectWidgets.askService = { answer(State.Connected) }
        runBlocking { ConnectWidgets.refresh(context).join() }
        assertEquals("Connected", text(id, R.id.widget_state))
        // Showing the service running, the widget watches it, without creating it.
        assertEquals(listOf(service), bound)
        // The service's process dies: no state change reaches this process, only the lost connection.
        ConnectWidgets.askService = { null }
        ConnectWidgets.watch.onServiceDisconnected(service)
        drawn()
        assertEquals("Not connected", text(id, R.id.widget_state))
        // Nothing runs any more: the watch lets go.
        assertEquals(listOf(service, null), bound)
    }

    @Test
    fun renamesAndRemovalsOfWhatShowsDrawAgain_trafficDoesNot() {
        ConfigBuilderTestEnv.io { profile(18, "Osaka 005") }
        ConfigBuilderTestEnv.io { profile(19, "Kyoto 006") }
        DataStore.favoriteProfiles = listOf(18L)
        val id = place()
        runBlocking {
            // The selected profile renamed in its editor: no selection or state change.
            val renamed = ConfigBuilderTestEnv.io {
                ProfileDatabase.proxyDao.updateBean(
                    17L,
                    SOCKSBean().apply {
                        initializeDefaultValues()
                        name = "Tokyo 004 bis"
                    },
                )
            }!!
            ProfileManager.postUpdate(renamed)
            drawn()
            assertEquals("Tokyo 004 bis", text(id, R.id.widget_profile))
            // Its group renamed.
            val renamedGroup = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.getById(group) }!!.apply { name = "Mullvad 2" }
            ConfigBuilderTestEnv.io { runBlocking { GroupManager.updateGroup(renamedGroup) } }
            drawn()
            assertEquals(" · Mullvad 2", text(id, R.id.widget_group))
            // Traffic counts and profiles that do not show leave the widget alone.
            val before = ConnectWidgets.lastRender
            ProfileManager.postUpdate(TrafficData(id = 17L, rx = 1L, tx = 1L))
            ConfigBuilderTestEnv.io { runBlocking { ProfileManager.deleteProfile(group, 19L) } }
            assertTrue(before === ConnectWidgets.lastRender)
            // A favorite on a chip removed, the selection as it was: drawn again.
            ConfigBuilderTestEnv.io { runBlocking { ProfileManager.deleteProfile(group, 18L) } }
            assertFalse(before === ConnectWidgets.lastRender)
            drawn()
            assertEquals("Tokyo 004 bis", text(id, R.id.widget_profile))
        }
    }

    @Test
    fun aRedrawIsFollowedByTheLatestSpeed_onceTheScreenIsOn_onEveryPlacedWidget() {
        val publish = ConnectWidgets.publishSpeed
        val posted = mutableListOf<Pair<List<Int>, Pair<String, String>>>()
        try {
            ConnectWidgets.publishSpeed = { _, ids, lines -> posted += ids.toList() to lines }
            val first = place()
            val a = SpeedDisplayData(txRateProxy = 1_000, rxRateProxy = 2_000)
            val b = SpeedDisplayData()
            ConnectWidgets.redrawn(context, a, now = true)
            ConnectWidgets.postSpeed(context, b)
            assertEquals(2, posted.size)
            // Screen off: a redraw showed an older speed, and nothing may go out now.
            ConnectWidgets.redrawn(context, b, now = false)
            assertEquals(2, posted.size)
            // Screen on: the unchanged speed goes out once, then not again.
            ConnectWidgets.postSpeed(context, b)
            ConnectWidgets.postSpeed(context, b)
            assertEquals(3, posted.size)
            assertEquals(posted[1].second, posted[2].second)
            // A widget placed since: the redraw for it reaches both widgets with the unchanged speed.
            val second = place()
            ConnectWidgets.redrawn(context, b, now = true)
            assertEquals(4, posted.size)
            assertEquals(setOf(first, second), posted.last().first.toSet())
            // A post Android refused is made again at the next round.
            ConnectWidgets.publishSpeed = { _, _, _ -> throw IllegalStateException("refused") }
            ConnectWidgets.redrawn(context, b, now = true)
            ConnectWidgets.publishSpeed = { _, ids, lines -> posted += ids.toList() to lines }
            ConnectWidgets.postSpeed(context, b)
            assertEquals(5, posted.size)
        } finally {
            ConnectWidgets.publishSpeed = publish
        }
    }

    @Test
    fun aFavoriteRemovedWithOthersLeavesTheChips() {
        ConfigBuilderTestEnv.io { profile(18, "Osaka 005") }
        ConfigBuilderTestEnv.io { profile(19, "Kyoto 006") }
        DataStore.favoriteProfiles = listOf(18L, 19L)
        place()
        assertTrue(18L in ConnectWidgets.shownProfiles && 19L in ConnectWidgets.shownProfiles)
        // Removed together from the list, as the list's selection mode removes them; the selection stays.
        val removed = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getEntities(listOf(18L, 19L)) }
        runBlocking { ConfigBuilderTestEnv.io { runBlocking { ProfileManager.deleteProfiles(removed) } } }
        drawn()
        assertEquals(setOf(17L), ConnectWidgets.shownProfiles)
    }

    @Test
    fun theCheckIsAskedForAtEveryDrawingWhileRunning_andCancelledOff() {
        val setCheck = ConnectWidgets.setCheck
        val checks = mutableListOf<Boolean>()
        try {
            ConnectWidgets.setCheck = { _, running -> checks += running }
            place()
            ConnectWidgets.askService = { answer(State.Connected) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            runBlocking { ConnectWidgets.refresh(context).join() }
            ConnectWidgets.askService = { null }
            runBlocking { ConnectWidgets.refresh(context).join() }
            // No answer kept from an earlier request: each drawing asks again.
            assertEquals(listOf(false, true, true, false), checks)
        } finally {
            ConnectWidgets.setCheck = setCheck
        }
    }
}
