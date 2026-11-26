package xyz.nekobyte.nekobox.bg

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ui.VpnRequestActivity
import android.service.quicksettings.TileService as BaseTileService

/** How the tile shows a state: its icon, its Tile state, and the subtitle under its label (null: the profile's name). */
internal data class TileLook(val icon: Int, val tileState: Int, val subtitle: Int?)

/**
 * Idle is what the binder answers once the service's data is gone, as while it is torn down: it shows as off. The tile
 * runs in the service's process, so a state it did not expect must not throw.
 */
internal fun tileLook(state: BaseService.State) = when (state) {
    BaseService.State.Connecting -> TileLook(R.drawable.ic_service_busy, Tile.STATE_ACTIVE, R.string.connecting)
    BaseService.State.Connected -> TileLook(R.drawable.ic_service_active, Tile.STATE_ACTIVE, null)
    BaseService.State.Stopping -> TileLook(R.drawable.ic_service_busy, Tile.STATE_UNAVAILABLE, R.string.stopping)
    BaseService.State.Idle, BaseService.State.Stopped -> TileLook(R.drawable.ic_service_idle, Tile.STATE_INACTIVE, R.string.off)
}

class TileService :
    BaseTileService(),
    NekoBoxConnection.Callback {
    private var tapPending = false

    private val connection = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = updateTile(state, profileName)

    override fun onServiceConnected(service: INekoBoxService) {
        updateTile(stateOf(service), service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    private fun stateOf(service: INekoBoxService) = BaseService.State.entries.getOrNull(service.state) ?: BaseService.State.Idle

    // Delivered on the main thread: read the title the service set for the new pick instead of
    // querying the profile database here.
    override fun cbSelectorUpdate(id: Long) = updateTile(BaseService.State.Connected, connection.service?.profileName)

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            val look = tileLook(serviceState)
            icon = Icon.createWithResource(this@TileService, look.icon)
            state = look.tileState
            val subtitleText = look.subtitle?.let(::getString) ?: profileName
            if (Build.VERSION.SDK_INT >= 29) {
                label = getString(R.string.app_name)
                subtitle = subtitleText
            } else {
                // Without a subtitle the label names the profile while connected.
                label = if (serviceState == BaseService.State.Connected) profileName ?: getString(R.string.app_name) else getString(R.string.app_name)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (serviceState == BaseService.State.Connected) getString(R.string.service_connected) else subtitleText
            }
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) {
            tapPending = true
            return
        }
        val state = stateOf(service)
        when {
            state.canStop -> NekoBox.stopService()
            state == BaseService.State.Stopped && needsVpnConsent() -> askForVpnConsent()
            state == BaseService.State.Stopped -> NekoBox.startService()
        }
    }

    private fun needsVpnConsent() = DataStore.serviceMode == Key.MODE_VPN && VpnService.prepare(this) != null

    // The app's consent screen asks for Android's VPN consent, then connects; a service cannot open it from here.
    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent form only before Android 14, which has no other.
    private fun askForVpnConsent() {
        val intent = Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
