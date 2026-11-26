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
 * Android's VPN consent. Only the widget's own intents reach it.
 */
class ConnectWidgetActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PROFILE = "xyz.nekobyte.nekobox.EXTRA_WIDGET_PROFILE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chipId = intent.getLongExtra(EXTRA_PROFILE, -1L)
        lifecycleScope.launch {
            val action = withContext(Dispatchers.Default) {
                val state = NekoBoxConnection.ask(applicationContext) { it.state }?.let { BaseService.State.entries.getOrNull(it) } ?: BaseService.State.Stopped
                // The id comes from an intent: it acts only once it names a profile in the database.
                val chip = chipId.takeIf { it > 0L }?.let { ProfileDatabase.proxyDao.getById(it) }
                val selectedId = DataStore.selectedProxy
                val action = widgetAction(
                    state = state,
                    chipId = chipId,
                    chipExists = chip != null,
                    selectedId = selectedId,
                    selectedExists = ProfileDatabase.proxyDao.getById(selectedId) != null,
                    autoSelecting = chip != null && state.canStop && runningGroupSelectsAutomatically(chip.groupId),
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
                WidgetAction.SELECT, WidgetAction.NONE -> Unit
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
