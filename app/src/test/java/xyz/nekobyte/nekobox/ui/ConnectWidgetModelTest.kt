package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService.State
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ArchivedBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

class ConnectWidgetModelTest {
    @Test
    fun chipsOfferFavoritesInPinOrder_thenRecentPicksOfTheSelectedListFirst() {
        val order = widgetChipOrder(
            favorites = listOf(7L, 3L),
            recentPicks = mapOf(1L to listOf(11L, 3L), 2L to listOf(21L, 22L)),
            selectedGroup = 2L,
        )
        assertEquals(listOf(7L, 3L, 21L, 22L, 11L), order)
    }

    @Test
    fun noFavoritesAndNoPicksOfferNoChips() {
        assertTrue(widgetChipOrder(emptyList(), emptyMap(), selectedGroup = 1L).isEmpty())
        // Ids that name no profile are not offered.
        assertEquals(listOf(5L), widgetChipOrder(listOf(0L, -1L, 5L), mapOf(1L to listOf(5L)), selectedGroup = 1L))
    }

    private fun socks(id: Long) = ProxyEntity(id = id).putBean(SOCKSBean().apply { initializeDefaultValues() })

    @Test
    fun deletedPinsBeforeAValidOneHideNoChip() {
        // Sixteen pins of deleted profiles, then one that exists: it is offered.
        val order = (1001L..1016L).toList() + 5L
        assertEquals(listOf(5L), widgetChipProfiles(order, listOf(socks(5))).map { it.id })
        // At most eight, in the order given.
        val many = (1L..12L).toList()
        assertEquals((1L..8L).toList(), widgetChipProfiles(many, many.reversed().map(::socks)).map { it.id })
    }

    @Test
    fun archivedProfilesAreNotOffered() {
        val archived = ProxyEntity(id = 7).putBean(ArchivedBean(7, byteArrayOf()))
        assertEquals(listOf(5L), widgetChipProfiles(listOf(7L, 5L), listOf(archived, socks(5))).map { it.id })
    }

    @Test
    fun theThresholdsAreTheBoundaries() {
        val wide = 400f
        assertEquals(WidgetSpec(orbOnly = true), widgetSpec(WIDGET_LINES_MIN_WIDTH - 1f, 300f))
        assertEquals(false, widgetSpec(WIDGET_LINES_MIN_WIDTH, 100f).orbOnly)
        // A three-cell widget of a five-column home screen keeps its lines.
        assertEquals(WidgetSpec(orbOnly = false), widgetSpec(203f, 90f))
        assertEquals(0, widgetSpec(wide, WIDGET_CHIPS_MIN_HEIGHT - 1f).chipRows)
        assertEquals(1, widgetSpec(wide, WIDGET_CHIPS_MIN_HEIGHT).chipRows)
        assertEquals(1, widgetSpec(wide, WIDGET_TWO_ROWS_MIN_HEIGHT - 1f).chipRows)
        assertEquals(2, widgetSpec(wide, WIDGET_TWO_ROWS_MIN_HEIGHT).chipRows)
    }

    @Test
    fun sizesPickTheOrbAlone_theLines_orRowsOfChips() {
        assertEquals(WidgetSpec(orbOnly = true), widgetSpec(70f, 90f))
        assertEquals(WidgetSpec(orbOnly = true), widgetSpec(150f, 300f))
        assertEquals(WidgetSpec(orbOnly = false), widgetSpec(250f, 90f))
        assertEquals(WidgetSpec(false, chipColumns = 1, chipRows = 1), widgetSpec(210f, 150f))
        assertEquals(WidgetSpec(false, chipColumns = 2, chipRows = 1), widgetSpec(360f, 150f))
        assertEquals(WidgetSpec(false, chipColumns = 3, chipRows = 1), widgetSpec(400f, 150f))
        assertEquals(WidgetSpec(false, chipColumns = 4, chipRows = 2), widgetSpec(900f, 400f))
    }

    @Test
    fun eachOrientationTakesItsOwnSize() {
        // Android 12 and later report a size for each orientation: upright narrow and tall, sideways wide and low.
        val reported = widgetSizes(listOf(300f to 200f, 420f to 110f), 0, 0, 0, 0)
        assertEquals(WidgetSizes(portrait = 300f to 200f, landscape = 420f to 110f), reported)
        // Two rows of chips upright, where the sideways height has room for none.
        assertEquals(2, widgetSpec(reported.portrait.first, reported.portrait.second).chipRows)
        assertEquals(0, widgetSpec(reported.landscape.first, reported.landscape.second).chipRows)
        // Before, the minimum width and the maximum height are the upright ones, the others the sideways ones.
        assertEquals(WidgetSizes(250f to 200f, 400f to 90f), widgetSizes(emptyList(), 250, 90, 400, 200))
        // Only minimums: both orientations take them.
        assertEquals(WidgetSizes(250f to 90f, 250f to 90f), widgetSizes(emptyList(), 250, 90, 0, 0))
        // Nothing reported: the lines without chips.
        val none = widgetSizes(emptyList(), 0, 0, 0, 0)
        assertEquals(WidgetSpec(orbOnly = false), widgetSpec(none.portrait.first, none.portrait.second))
        assertEquals(WidgetSpec(orbOnly = false), widgetSpec(none.landscape.first, none.landscape.second))
    }

    private fun orb(state: State, selectedUsable: Boolean = true) = widgetAction(state, -1L, false, 1L, selectedUsable, false)

    @Test
    fun theOrbConnectsAndDisconnects() {
        assertEquals(WidgetAction.STOP, orb(State.Connected))
        assertEquals(WidgetAction.STOP, orb(State.Connecting))
        assertEquals(WidgetAction.START, orb(State.Stopped))
        // A service on its way out answers Idle: a tap starts once it is gone, as from off.
        assertEquals(WidgetAction.START, orb(State.Idle))
        assertEquals(WidgetAction.NONE, orb(State.Stopping))
        // Nothing to connect to: the app says why.
        assertEquals(WidgetAction.OPEN_APP, orb(State.Stopped, selectedUsable = false))
    }

    private fun chip(state: State, id: Long = 2L, usable: Boolean = true, autoSelecting: Boolean = false) = widgetAction(state, id, usable, 1L, true, autoSelecting)

    @Test
    fun aChipSwitchesARunningConnection_orSelectsAndConnects() {
        assertEquals(WidgetAction.SWITCH, chip(State.Connected))
        assertEquals(WidgetAction.SWITCH, chip(State.Connecting))
        assertEquals(WidgetAction.SELECT_AND_START, chip(State.Stopped))
        assertEquals(WidgetAction.SELECT_AND_START, chip(State.Idle))
        assertEquals(WidgetAction.SELECT, chip(State.Stopping))
        // The profile already running stays as it is (activeId is the service's, not this process's selection).
        assertEquals(WidgetAction.NONE, chip(State.Connected, id = 1L))
    }

    @Test
    fun aChipOfAProfileGone_orOfAGroupPickingItsOwnServer_opensTheApp() {
        assertEquals(WidgetAction.OPEN_APP, chip(State.Connected, usable = false))
        assertEquals(WidgetAction.OPEN_APP, chip(State.Stopped, usable = false))
        assertEquals(WidgetAction.OPEN_APP, chip(State.Connected, autoSelecting = true))
    }

    @Test
    fun idleReadsAsOff() {
        assertEquals(R.string.not_connected, widgetStateText(State.Idle))
        assertEquals(R.string.not_connected, widgetStateText(State.Stopped))
        assertEquals(R.string.service_connected, widgetStateText(State.Connected))
        assertEquals(R.string.connecting, widgetStateText(State.Connecting))
        assertEquals(R.string.stopping, widgetStateText(State.Stopping))
    }

    @Test
    fun aRedrawShowingAnOlderSpeedGetsTheLatestBack() {
        val posts = SpeedPosts()
        val a = "1 kB/s" to "2 kB/s"
        val b = "0 B/s" to "0 B/s"
        fun post(lines: Pair<String, String>, force: Boolean = false) = posts.shouldPost(lines, force).also { if (it) posts.posted(lines) }
        assertTrue(post(a))
        assertTrue(post(b))
        // A redraw drew the older A over B: the service posts B again, unchanged as it is.
        assertTrue(post(b, force = true))
        // Then B as long as it stays is not posted again.
        assertFalse(post(b))
    }

    @Test
    fun aSpeedAndroidDidNotTakeIsPostedAgain() {
        val posts = SpeedPosts()
        val a = "1 kB/s" to "2 kB/s"
        // The update failed before posted(): the same pair still goes out next round.
        assertTrue(posts.shouldPost(a, force = false))
        assertTrue(posts.shouldPost(a, force = false))
        posts.posted(a)
        assertFalse(posts.shouldPost(a, force = false))
    }

    @Test
    fun theActiveChipIsTheProfileTheServiceRuns() {
        // Running 30 (its group picked it) while this process still has 17 selected.
        val running = TapState(State.Connected, runningProfileId = 30L)
        assertEquals(30L, widgetActiveId(running, selectedId = 17L))
        assertEquals(WidgetAction.NONE, widgetAction(State.Connected, 30L, true, widgetActiveId(running, 17L), true, false))
        assertEquals(WidgetAction.SWITCH, widgetAction(State.Connected, 17L, true, widgetActiveId(running, 17L), true, false))
        // Off, or the service names none yet: the selection.
        assertEquals(17L, widgetActiveId(TapState(State.Stopped), 17L))
        assertEquals(17L, widgetActiveId(TapState(State.Connecting, -1L), 17L))
    }

    @Test
    fun aTapOnAWidgetShowingAnotherStateOnlyRedraws() {
        // Both processes ended with the widget on Connected: no service is found, and the tap must not start one.
        assertTrue(widgetTapStale(drawn = State.Connected, live = State.Stopped))
        assertTrue(widgetTapStale(drawn = State.Stopped, live = State.Connected))
        assertTrue(widgetTapStale(drawn = State.Connecting, live = State.Connected))
        // An intent without the drawn state comes from no drawing of this version.
        assertTrue(widgetTapStale(drawn = null, live = State.Stopped))
        // As shown, it acts; Idle shows as off.
        assertFalse(widgetTapStale(drawn = State.Connected, live = State.Connected))
        assertFalse(widgetTapStale(drawn = State.Stopped, live = State.Idle))
        assertFalse(widgetTapStale(drawn = State.Idle, live = State.Stopped))
        // A service slow to answer stays unknown: widgetAction opens the app.
        assertFalse(widgetTapStale(drawn = State.Connected, live = null))
        assertEquals(WidgetAction.OPEN_APP, widgetAction(null, -1L, false, 1L, true, false))
    }

    @Test
    fun onlyTheTapActionCarriesADrawnState() {
        assertEquals(State.Connected, widgetDrawnState(ConnectWidgetActivity.ACTION_TAP + "Connected"))
        assertEquals(State.Idle, widgetDrawnState(ConnectWidgetActivity.ACTION_TAP + "Idle"))
        // A bare state name, a foreign action, an unknown name or none: no drawn state, so the tap only redraws.
        for (action in listOf("Stopped", "Connected", "android.intent.action.MAIN", ConnectWidgetActivity.ACTION_TAP + "Bogus", ConnectWidgetActivity.ACTION_TAP, null)) {
            assertEquals(null, widgetDrawnState(action))
            assertTrue(widgetTapStale(widgetDrawnState(action), State.Stopped))
            assertTrue(widgetTapStale(widgetDrawnState(action), State.Connected))
        }
    }

    @Test
    fun chipsSayWhatATapDoes() {
        assertEquals(R.string.widget_chip_selected, widgetChipDescription(State.Connected, selected = true))
        assertEquals(R.string.widget_chip_switch, widgetChipDescription(State.Connected, selected = false))
        // Off, a tap on the selected chip connects it.
        assertEquals(R.string.widget_chip_connect, widgetChipDescription(State.Stopped, selected = true))
        assertEquals(R.string.widget_chip_connect, widgetChipDescription(State.Stopped, selected = false))
    }
}
