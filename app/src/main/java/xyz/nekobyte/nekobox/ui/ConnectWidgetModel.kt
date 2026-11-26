package xyz.nekobyte.nekobox.ui

import android.content.ComponentName
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.ProxyEntity

/** What one size of the connect widget shows: the orb alone, or the orb with the connection's lines and [chipColumns] by [chipRows] quick-switch chips. */
internal data class WidgetSpec(val orbOnly: Boolean, val chipColumns: Int = 0, val chipRows: Int = 0)

// What widget_connect needs, measured with real fonts at font scale 1.3 (ConnectWidgetLayoutTest). Under the lines
// (94 dp high: 24 dp of padding and the three text lines, taller than the 56 dp orb), a row of chips takes 4 + 48 dp
// and a second one 48 dp more; lower, fewer chip rows show. Beside the orb, 92 dp go to padding, orb and margins: from
// 180 dp wide the lines show, which keeps them on a three-cell widget of a five-column home screen (about 203 dp);
// there the profile, the state and the speed end in "…" where they do not fit. Narrower, the orb shows alone.
internal const val WIDGET_LINES_MIN_WIDTH = 180f
internal const val WIDGET_CHIPS_MIN_HEIGHT = 146f
internal const val WIDGET_TWO_ROWS_MIN_HEIGHT = 194f
private const val CHIP_MIN_WIDTH = 120f
private const val CHIP_ROW_PADDING = 16f
private const val MAX_CHIP_COLUMNS = 4

// Two rows of four, the most a widget shows.
internal const val WIDGET_MAX_CHIPS = 8

internal fun widgetSpec(widthDp: Float, heightDp: Float): WidgetSpec = when {
    widthDp < WIDGET_LINES_MIN_WIDTH -> WidgetSpec(orbOnly = true)

    heightDp < WIDGET_CHIPS_MIN_HEIGHT -> WidgetSpec(orbOnly = false)

    else -> WidgetSpec(
        orbOnly = false,
        chipColumns = ((widthDp - CHIP_ROW_PADDING) / CHIP_MIN_WIDTH).toInt().coerceIn(1, MAX_CHIP_COLUMNS),
        chipRows = if (heightDp >= WIDGET_TWO_ROWS_MIN_HEIGHT) 2 else 1,
    )
}

/**
 * The size, width to height in dp, a layout must fit to show in every orientation: the smallest width and height of
 * the [sizes] the home screen reports (Android 12 and later), else the minimum width and height it reports, which are
 * the upright width and the sideways height. Zero where it reports nothing: the lines without chips.
 */
internal fun widgetFitSize(sizes: List<Pair<Float, Float>>, minWidth: Int, minHeight: Int): Pair<Float, Float> = if (sizes.isNotEmpty()) {
    sizes.minOf { it.first } to sizes.minOf { it.second }
} else {
    (minWidth.takeIf { it > 0 }?.toFloat() ?: WIDGET_LINES_MIN_WIDTH) to (minHeight.takeIf { it > 0 }?.toFloat() ?: 40f)
}

/**
 * The profiles the chips offer, in order: the favorites as pinned, then what was picked lately, from the list of
 * [selectedGroup] first and then from the others. Each profile once.
 */
internal fun widgetChipOrder(favorites: List<Long>, recentPicks: Map<Long, List<Long>>, selectedGroup: Long): List<Long> {
    val recent = recentPicks[selectedGroup].orEmpty() + recentPicks.filterKeys { it != selectedGroup }.values.flatten()
    return (favorites + recent).filter { it > 0L }.distinct()
}

/**
 * The chips' profiles: those of [order] that exist and that this version can connect with (not archived), in its
 * order, at most [WIDGET_MAX_CHIPS].
 */
internal fun widgetChipProfiles(order: List<Long>, profiles: List<ProxyEntity>): List<ProxyEntity> {
    val byId = profiles.associateBy { it.id }
    return order.mapNotNull(byId::get).filter { it.canBuild() }.take(WIDGET_MAX_CHIPS)
}

/** The live speed's line: upload first, as the end of a narrow line is what "…" takes. */
internal fun widgetSpeedLine(tx: String, rx: String) = "▲ $tx   ▼ $rx"

/** What a tap on the widget does. */
internal enum class WidgetAction { STOP, START, SWITCH, SELECT_AND_START, SELECT, OPEN_APP, REDRAW, NONE }

/**
 * Whether a tap found the service in a state other than the one the widget showed ([drawn], null when the tap did not
 * say): then the widget is stale, both processes having ended unseen for one, and the tap only redraws it, as acting
 * on it could do the opposite of what was meant (start a connection the widget showed as running). An unknown [live]
 * state is not stale: [widgetAction] opens the app. Idle shows as off.
 */
internal fun widgetTapStale(drawn: BaseService.State?, live: BaseService.State?): Boolean {
    fun shown(state: BaseService.State?) = if (state == BaseService.State.Idle) BaseService.State.Stopped else state
    return live != null && shown(drawn) != shown(live)
}

/**
 * What a tap acts on: the service's [state], null when a service was started but did not answer in time, and the
 * profile it runs (-1 none or unknown).
 */
internal class TapState(val state: BaseService.State?, val runningProfileId: Long = -1L)

/**
 * The state for a tap: Stopped when no service was [started], the first answer of those that were, and unknown when
 * none answered: a live VPN that was slow to answer must not be taken for a stopped one.
 */
internal suspend fun tapState(started: List<ComponentName>, ask: suspend (ComponentName) -> ConnectWidgets.ServiceAnswer?): TapState {
    if (started.isEmpty()) return TapState(BaseService.State.Stopped)
    for (service in started) ask(service)?.takeIf { it.state != null }?.let { return TapState(it.state, it.runningProfileId) }
    return TapState(null)
}

/** The profile a running service runs, as the service said; else the selected one. */
internal fun widgetActiveId(tap: TapState, selectedId: Long) = tap.runningProfileId.takeIf { tap.state?.started == true && it > 0L } ?: selectedId

/**
 * A tap on the orb ([chipId] < 0) or on the chip of profile [chipId], which can connect when [chipUsable] (it exists
 * and is not archived), as can the selected profile when [selectedUsable]. A chip switches a running connection to its
 * profile (none to do when it is [activeId], the one the service runs), or selects it and connects. Where the app has more to say (no profile to connect, a group that picks its
 * own server, a profile gone or archived since, a service whose state is unknown), the app opens.
 */
internal fun widgetAction(
    state: BaseService.State?,
    chipId: Long,
    chipUsable: Boolean,
    activeId: Long,
    selectedUsable: Boolean,
    autoSelecting: Boolean,
): WidgetAction {
    if (state == null) return WidgetAction.OPEN_APP
    if (chipId < 0L) {
        return when {
            state.canStop -> WidgetAction.STOP
            state == BaseService.State.Stopping -> WidgetAction.NONE
            selectedUsable -> WidgetAction.START
            else -> WidgetAction.OPEN_APP
        }
    }
    if (!chipUsable) return WidgetAction.OPEN_APP
    return when {
        state == BaseService.State.Stopping -> WidgetAction.SELECT
        !state.canStop -> WidgetAction.SELECT_AND_START
        chipId == activeId -> WidgetAction.NONE
        autoSelecting -> WidgetAction.OPEN_APP
        else -> WidgetAction.SWITCH
    }
}

/**
 * What TalkBack says for a chip, with its profile's name: what a tap does. The chip of the profile running says it is
 * selected, as a tap leaves it; off, every chip connects, the selected one too.
 */
internal fun widgetChipDescription(state: BaseService.State, selected: Boolean) = when {
    selected && state.canStop -> R.string.widget_chip_selected
    state.canStop -> R.string.widget_chip_switch
    else -> R.string.widget_chip_connect
}

/** The widget's words for [state]; Idle is how a service that is going away answers, so it reads as off. */
internal fun widgetStateText(state: BaseService.State) = when (state) {
    BaseService.State.Connecting -> R.string.connecting
    BaseService.State.Connected -> R.string.service_connected
    BaseService.State.Stopping -> R.string.stopping
    BaseService.State.Idle, BaseService.State.Stopped -> R.string.not_connected
}

/**
 * The speed lines the service's process posted last: an unchanged pair is not posted again, unless forced after a
 * redraw of the widgets, which may have shown an older one. A pair counts as posted once Android took it.
 */
internal class SpeedPosts {
    private var posted: Pair<String, String>? = null

    @Synchronized
    fun shouldPost(lines: Pair<String, String>, force: Boolean) = force || lines != posted

    @Synchronized
    fun posted(lines: Pair<String, String>) {
        posted = lines
    }
}
