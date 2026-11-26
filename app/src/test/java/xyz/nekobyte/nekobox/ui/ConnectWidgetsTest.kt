package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
        try {
            drawn()
            // Lets go of a watched service: the watch outlives a test.
            ConnectWidgets.askService = { null }
            runBlocking { withTimeout(10_000) { ConnectWidgets.refresh(context).join() } }
        } finally {
            // The hooks and listeners are global: they go back even when a drawing above fails.
            ConnectWidgets.bindWatch = bindWatch
            ConnectWidgets.unbindWatch = unbindWatch
            ConnectWidgets.askService = ask
            ProfileManager.removeListener(ConnectWidgets.modelListener)
            GroupManager.removeListener(ConnectWidgets.modelListener)
            Logs.sink = logSink
        }
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

    // ConnectWidgetActivity compares it with the service's state: a widget left on another one only redraws.
    @Test
    fun aTapCarriesTheStateItsWidgetShows() {
        val id = place()
        fun tapped(): String? {
            manager.getViewFor(id).findViewById<View>(R.id.widget_orb).performClick()
            return shadowOf(context).nextStartedActivity.action
        }
        assertEquals(ConnectWidgetActivity.ACTION_TAP + State.Stopped.name, tapped())
        ConnectWidgets.askService = { answer(State.Connected) }
        runBlocking { ConnectWidgets.refresh(context).join() }
        assertEquals(ConnectWidgetActivity.ACTION_TAP + State.Connected.name, tapped())
    }

    // The home screen can show a drawing after a newer one was made, until it applies that one: a tap on it must still
    // carry the state it shows, or a Connected orb tapped once the service stopped would start it again.
    @Test
    fun aDrawingStillOnScreenKeepsTheStateItShows() {
        ConfigBuilderTestEnv.io { profile(18, "Osaka 005") }
        DataStore.favoriteProfiles = listOf(18L)
        val id = place()
        val sizes = arrayListOf(SizeF(300f, 200f))
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(id, Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) })
        ConnectWidgets.askService = { answer(State.Connected) }
        runBlocking { ConnectWidgets.refresh(context).join() }
        val shown = manager.getViewFor(id)
        val taps = listOf(R.id.widget_orb, R.id.widget_chip).map { shown.findViewById<View>(it) }.map { it to shadowOf(it).onClickListener }
        // A Stopped drawing is made; the Connected one's tap targets are still the ones on screen.
        ConnectWidgets.askService = { null }
        runBlocking { ConnectWidgets.refresh(context).join() }
        for ((view, tap) in taps) {
            tap.onClick(view)
            assertEquals(ConnectWidgetActivity.ACTION_TAP + State.Connected.name, shadowOf(context).nextStartedActivity.action)
        }
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

    // The 4x3 case of a Pixel home screen: upright tall enough for two rows of chips, sideways for none.
    @Test
    fun eachOrientationShowsTheChipRowsItsSizeHasRoomFor() {
        for (profileId in 18L..20L) ConfigBuilderTestEnv.io { profile(profileId, "Osaka $profileId") }
        DataStore.favoriteProfiles = listOf(18L, 19L, 20L)
        val id = place()
        val sizes = arrayListOf(SizeF(300f, 200f), SizeF(420f, 110f))
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(id, Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) })
        fun rows(): Int {
            runBlocking { ConnectWidgets.refresh(context).join() }
            return listOf(R.id.widget_chips_1, R.id.widget_chips_2).count { manager.getViewFor(id).findViewById<View>(it).visibility == View.VISIBLE }
        }
        assertEquals(2, rows())
        RuntimeEnvironment.setQualifiers("+land")
        assertEquals(0, rows())
        // With the speed lines, only the current orientation's layout, drawn again after each rotation.
        ConnectWidgets.askService = { answer(State.Connected) }
        assertEquals(0, rows())
        RuntimeEnvironment.setQualifiers("+port")
        assertEquals(2, rows())
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

    @Test
    fun theLastWidgetRemovedLetsGoOfTheWatchAndTheCheck() {
        val setCheck = ConnectWidgets.setCheck
        val widgetIds = ConnectWidgets.widgetIds
        val checks = mutableListOf<Boolean>()
        val bound = mutableListOf<ComponentName?>()
        try {
            ConnectWidgets.setCheck = { _, running -> checks += running }
            place()
            ConnectWidgets.bindWatch = { _, intent ->
                bound += intent.component
                true
            }
            ConnectWidgets.unbindWatch = { bound += null }
            ConnectWidgets.askService = { answer(State.Connected) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            assertEquals(listOf(service), bound)
            assertTrue(17L in ConnectWidgets.shownProfiles)
            // The home screen removes the last widget and tells the provider.
            ConnectWidgets.widgetIds = { IntArray(0) }
            ConnectWidget().onDisabled(context)
            drawn()
            assertEquals(listOf(service, null), bound)
            assertEquals(false, checks.last())
            assertTrue(ConnectWidgets.shownProfiles.isEmpty())
        } finally {
            ConnectWidgets.setCheck = setCheck
            ConnectWidgets.widgetIds = widgetIds
        }
    }

    @Test
    fun aServiceThatDoesNotAnswerInTimeIsNotTakenForStopped() = runBlocking {
        val connected = answer(State.Connected, running = 17L)
        // None started: off.
        assertEquals(State.Stopped, tapState(emptyList()) { connected }.state)
        // Started and answering.
        assertEquals(State.Connected, tapState(listOf(service)) { connected }.state)
        // Started, but the answer comes after the wait for it ends: unknown, and a tap opens the app instead of starting.
        val late = tapState(listOf(service)) {
            withTimeoutOrNull(100) {
                delay(1_000)
                connected
            }
        }
        assertEquals(null, late.state)
        assertEquals(WidgetAction.OPEN_APP, widgetAction(late.state, -1L, false, 1L, true, false))
        assertEquals(WidgetAction.OPEN_APP, widgetAction(late.state, 2L, true, 1L, true, false))
    }

    @Test
    fun theAutoSelectGuardFollowsTheProfileTheServiceRuns() {
        // A group whose server urltest picks, running; this process has no current profile of its own.
        val auto = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Auto", isSelector = true, autoSelect = true)) }
        ConfigBuilderTestEnv.io {
            ProfileDatabase.proxyDao.addProxy(ProxyEntity(id = 30, groupId = auto).putBean(SOCKSBean().apply { initializeDefaultValues() }))
        }
        DataStore.currentProfile = 0L
        ConfigBuilderTestEnv.io {
            assertFalse(runningGroupSelectsAutomatically(auto))
            assertTrue(runningGroupSelectsAutomatically(auto, runningProfileId = 30L))
            assertFalse(runningGroupSelectsAutomatically(group, runningProfileId = 30L))
        }
    }

    // The service frozen or busy: its answer comes after the wait for it ends.
    private suspend fun late(answer: ServiceAnswer) = withTimeoutOrNull(50) {
        delay(1_000)
        answer
    } ?: ServiceAnswer(service, null, -1L, null)

    @Test
    fun aServiceSlowToAnswerStaysAsItLastAnswered_watched_checked_andAskedAgain() {
        val retryMs = ConnectWidgets.retryMs
        val setCheck = ConnectWidgets.setCheck
        val checks = mutableListOf<Boolean>()
        val bound = mutableListOf<ComponentName?>()
        try {
            ConnectWidgets.retryMs = 100
            ConnectWidgets.setCheck = { _, running -> checks += running }
            val id = place()
            ConnectWidgets.bindWatch = { _, intent ->
                bound += intent.component
                true
            }
            ConnectWidgets.unbindWatch = { bound += null }
            ConnectWidgets.askService = { answer(State.Connected, running = 17L) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            assertEquals("Connected", text(id, R.id.widget_state))
            ConnectWidgets.askService = { late(answer(State.Connected)) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            // Not taken for stopped: still Connected, still watched and checked.
            assertEquals("Connected", text(id, R.id.widget_state))
            assertEquals(listOf(service), bound)
            assertEquals(true, checks.last())
            // Asked again shortly; this time the service is gone, and the widget follows. The retry's drawing says when
            // it asks, and the test then waits for that drawing.
            val retried = CompletableDeferred<Unit>()
            ConnectWidgets.askService = {
                retried.complete(Unit)
                null
            }
            runBlocking { withTimeout(5_000) { retried.await() } }
            drawn()
            assertEquals("Not connected", text(id, R.id.widget_state))
            assertEquals(listOf(service, null), bound)
        } finally {
            ConnectWidgets.retryMs = retryMs
            ConnectWidgets.setCheck = setCheck
        }
    }

    @Test
    fun aServiceThatNeverAnsweredIsShownConnecting() {
        val retryMs = ConnectWidgets.retryMs
        try {
            ConnectWidgets.retryMs = 60_000
            val id = place()
            ConnectWidgets.askService = { late(answer(State.Connected)) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            assertEquals("Connecting…", text(id, R.id.widget_state))
        } finally {
            ConnectWidgets.retryMs = retryMs
        }
    }

    @Test
    fun aServiceStoppingStaysWatchedAndChecked_untilItIsGone() {
        val setCheck = ConnectWidgets.setCheck
        val checks = mutableListOf<Boolean>()
        val bound = mutableListOf<ComponentName?>()
        try {
            ConnectWidgets.setCheck = { _, running -> checks += running }
            val id = place()
            ConnectWidgets.bindWatch = { _, intent ->
                bound += intent.component
                true
            }
            ConnectWidgets.unbindWatch = { bound += null }
            ConnectWidgets.askService = { answer(State.Stopping) }
            runBlocking { ConnectWidgets.refresh(context).join() }
            assertEquals("Shutting down…", text(id, R.id.widget_state))
            assertEquals(listOf(service), bound)
            assertEquals(true, checks.last())
            // Its process dies during the cleanup: the lost connection draws the widget off.
            ConnectWidgets.askService = { null }
            ConnectWidgets.watch.onServiceDisconnected(service)
            drawn()
            assertEquals("Not connected", text(id, R.id.widget_state))
            assertEquals(listOf(service, null), bound)
            assertEquals(false, checks.last())
        } finally {
            ConnectWidgets.setCheck = setCheck
        }
    }

    @Test
    fun aProfileRenamedWhileADrawingReadsItIsDrawnAgain() {
        val hook = ConnectWidgets.afterProfilesRead
        ConfigBuilderTestEnv.io { profile(18, "Osaka 005") }
        try {
            val id = place()
            // The selection moves to a profile the widget does not show yet, and its editor saves a new name right
            // after the drawing read the old one.
            var renamed = false
            ConnectWidgets.afterProfilesRead = {
                if (!renamed) {
                    renamed = true
                    val profile = ConfigBuilderTestEnv.io {
                        ProfileDatabase.proxyDao.updateBean(
                            18L,
                            SOCKSBean().apply {
                                initializeDefaultValues()
                                name = "Osaka 005 bis"
                            },
                        )
                    }!!
                    ProfileManager.postUpdate(profile)
                }
            }
            DataStore.selectedProxy = 18L
            runBlocking { ConnectWidgets.refresh(context).join() }
            drawn()
            assertEquals("Osaka 005 bis", text(id, R.id.widget_profile))
        } finally {
            ConnectWidgets.afterProfilesRead = hook
        }
    }

    @Test
    fun aServiceThatNeverAnswersIsAskedAgainEveryTime_upToTheLimit() {
        val retryMs = ConnectWidgets.retryMs
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val fifth = CompletableDeferred<Unit>()
        try {
            ConnectWidgets.retryMs = 20
            val id = place()
            // Each retry's drawing finds the service still silent, at once: the next retry is due all the same.
            ConnectWidgets.askService = {
                if (asks.incrementAndGet() == 5) fifth.complete(Unit)
                ServiceAnswer(service, null, -1L, null)
            }
            runBlocking {
                ConnectWidgets.refresh(context).join()
                withTimeout(5_000) { fifth.await() }
                // Four retries after the first ask, then no more.
                delay(1_000)
            }
            drawn()
            assertEquals(5, asks.get())
            assertEquals("Connecting…", text(id, R.id.widget_state))
        } finally {
            ConnectWidgets.retryMs = retryMs
        }
    }
}
