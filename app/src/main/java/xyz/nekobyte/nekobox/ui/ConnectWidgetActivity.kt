package xyz.nekobyte.nekobox.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager

/**
 * Carries out a tap on the connect widget, without a window of its own: the orb connects or disconnects, a chip
 * switches the connection to its profile, or selects it and connects. An activity, so that a start may ask for
 * Android's VPN consent. Only the widget's own intents reach it. A tap on a widget showing another state than the
 * service's only redraws it.
 */
class ConnectWidgetActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PROFILE = "xyz.nekobyte.nekobox.EXTRA_WIDGET_PROFILE"

        // The state the widget showed when tapped (BaseService.State's ordinal).
        const val EXTRA_DRAWN_STATE = "xyz.nekobyte.nekobox.EXTRA_WIDGET_DRAWN_STATE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chipId = intent.getLongExtra(EXTRA_PROFILE, -1L)
        val drawn = BaseService.State.entries.getOrNull(intent.getIntExtra(EXTRA_DRAWN_STATE, -1))
        lifecycleScope.launch {
            val action = withContext(Dispatchers.Default) {
                val tap = tapState(NekoBoxConnection.startedServices()) { ConnectWidgets.ask(applicationContext, it) }
                val state = tap.state
                if (widgetTapStale(drawn, state)) {
                    ConnectWidgets.refresh(applicationContext).join()
                    return@withContext WidgetAction.REDRAW
                }
                // The id comes from an intent: it acts only once it names a profile in the database this version can
                // connect with. A chip drawn before its profile was archived or deleted opens the app instead.
                val chip = chipId.takeIf { it > 0L }?.let { ProfileDatabase.proxyDao.getById(it) }?.takeIf { it.canBuild() }
                val selectedId = DataStore.selectedProxy
                val activeId = widgetActiveId(tap, selectedId)
                val action = widgetAction(
                    state = state,
                    chipId = chipId,
                    chipUsable = chip != null,
                    // Running, the chip of the profile the service runs has nothing to switch to, also when its group
                    // picked it; this process's selection can be older.
                    activeId = activeId,
                    selectedUsable = ProfileDatabase.proxyDao.getById(selectedId)?.canBuild() == true,
                    autoSelecting = chip != null && state?.canStop == true &&
                        runningGroupSelectsAutomatically(chip.groupId, tap.runningProfileId.takeIf { it > 0L } ?: DataStore.currentProfile),
                )
                if (chip != null && action in setOf(WidgetAction.SWITCH, WidgetAction.SELECT_AND_START, WidgetAction.SELECT)) {
                    // As a pick in the app's lists: it leads its list's Recent, and the lists show the new selection.
                    val previous = DataStore.selectedProxy
                    DataStore.selectedProxy = chip.id
                    DataStore.rememberPick(chip.groupId, chip.id)
                    ProfileManager.postUpdate(previous, noTraffic = true)
                    ProfileManager.postUpdate(chip.id, noTraffic = true)
                }
                action
            }
            when (action) {
                WidgetAction.STOP -> NekoBox.stopService()
                WidgetAction.START, WidgetAction.SELECT_AND_START -> start()
                WidgetAction.SWITCH -> NekoBox.reloadService(chipId)
                WidgetAction.OPEN_APP -> startActivity(Intent(this@ConnectWidgetActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                WidgetAction.SELECT, WidgetAction.REDRAW, WidgetAction.NONE -> Unit
            }
            finish()
        }
    }

    // Without Android's VPN consent the app's consent screen asks for it, then connects.
    private fun start() {
        if (DataStore.serviceMode == Key.MODE_VPN && VpnService.prepare(this) != null) {
            startActivity(Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            NekoBox.startService()
        }
    }
}
