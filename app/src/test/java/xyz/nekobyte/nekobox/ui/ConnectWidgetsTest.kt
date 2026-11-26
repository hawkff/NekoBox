package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Binder
import android.os.Looper
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
import xyz.nekobyte.nekobox.bg.BaseService.State
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
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
    }

    @After
    fun tearDown() {
        drawn()
        // Lets go of a watched service: the watch outlives a test.
        ConnectWidgets.askService = { null }
        runBlocking { ConnectWidgets.refresh(context).join() }
        ConnectWidgets.askService = ask
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
        shadowOf(context).setComponentNameAndServiceForBindService(service, Binder())
        ConnectWidgets.askService = { answer(State.Connected) }
        runBlocking { ConnectWidgets.refresh(context).join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Connected", text(id, R.id.widget_state))
        assertTrue(ConnectWidgets.watch in shadowOf(context).boundServiceConnections)
        // The service's process dies: no state change reaches this process, only the lost connection.
        ConnectWidgets.askService = { null }
        ConnectWidgets.watch.onServiceDisconnected(service)
        drawn()
        assertEquals("Not connected", text(id, R.id.widget_state))
        // Nothing runs any more: the watch lets go.
        assertFalse(ConnectWidgets.watch in shadowOf(context).boundServiceConnections)
    }
}
