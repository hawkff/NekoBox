package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService.State

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

    @Test
    fun sizesPickTheOrbAlone_theLines_orRowsOfChips() {
        assertEquals(WidgetSpec(orbOnly = true), widgetSpec(70f, 90f))
        assertEquals(WidgetSpec(orbOnly = true), widgetSpec(150f, 300f))
        assertEquals(WidgetSpec(orbOnly = false), widgetSpec(250f, 90f))
        assertEquals(WidgetSpec(false, chipColumns = 1, chipRows = 1), widgetSpec(160f, 120f))
        assertEquals(WidgetSpec(false, chipColumns = 2, chipRows = 1), widgetSpec(360f, 150f))
        assertEquals(WidgetSpec(false, chipColumns = 3, chipRows = 1), widgetSpec(400f, 150f))
        assertEquals(WidgetSpec(false, chipColumns = 4, chipRows = 2), widgetSpec(900f, 400f))
    }

    @Test
    fun aLayoutFitsEveryOrientation() {
        // Android 12 and later report a size for each orientation: the smallest width and height fit both.
        assertEquals(300f to 110f, widgetFitSize(listOf(300f to 200f, 420f to 110f), 0, 0))
        // Before, the minimum width is the upright one, the minimum height the sideways one.
        assertEquals(250f to 90f, widgetFitSize(emptyList(), 250, 90))
        // Nothing reported: the lines without chips.
        assertEquals(WidgetSpec(orbOnly = false), widgetFitSize(emptyList(), 0, 0).let { (width, height) -> widgetSpec(width, height) })
    }

    private fun orb(state: State, selectedExists: Boolean = true) = widgetAction(state, -1L, false, 1L, selectedExists, false)

    @Test
    fun theOrbConnectsAndDisconnects() {
        assertEquals(WidgetAction.STOP, orb(State.Connected))
        assertEquals(WidgetAction.STOP, orb(State.Connecting))
        assertEquals(WidgetAction.START, orb(State.Stopped))
        // A service on its way out answers Idle: a tap starts once it is gone, as from off.
        assertEquals(WidgetAction.START, orb(State.Idle))
        assertEquals(WidgetAction.NONE, orb(State.Stopping))
        // Nothing to connect to: the app says why.
        assertEquals(WidgetAction.OPEN_APP, orb(State.Stopped, selectedExists = false))
    }

    private fun chip(state: State, id: Long = 2L, exists: Boolean = true, autoSelecting: Boolean = false) = widgetAction(state, id, exists, 1L, true, autoSelecting)

    @Test
    fun aChipSwitchesARunningConnection_orSelectsAndConnects() {
        assertEquals(WidgetAction.SWITCH, chip(State.Connected))
        assertEquals(WidgetAction.SWITCH, chip(State.Connecting))
        assertEquals(WidgetAction.SELECT_AND_START, chip(State.Stopped))
        assertEquals(WidgetAction.SELECT_AND_START, chip(State.Idle))
        assertEquals(WidgetAction.SELECT, chip(State.Stopping))
        // The profile already running stays as it is.
        assertEquals(WidgetAction.NONE, chip(State.Connected, id = 1L))
    }

    @Test
    fun aChipOfAProfileGone_orOfAGroupPickingItsOwnServer_opensTheApp() {
        assertEquals(WidgetAction.OPEN_APP, chip(State.Connected, exists = false))
        assertEquals(WidgetAction.OPEN_APP, chip(State.Stopped, exists = false))
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
}
