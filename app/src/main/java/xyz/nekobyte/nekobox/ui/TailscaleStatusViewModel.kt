package xyz.nekobyte.nekobox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.fmt.tailscale.TailscaleBean

class TailscaleStatusViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(TailscaleStatusUiState())
    internal val state = mutableState.asStateFlow()
    internal var profileName = ""
        private set
    private var initialized = false
    private var foreground = false
    internal var session: TailscaleStatusSession? = null
        private set

    internal fun initialize(profileId: Long) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching {
                    require(profileId > 0)
                    ProfileDatabase.proxyDao.getById(profileId)?.takeIf { it.requireBean() is TailscaleBean }
                }.getOrNull()
            }
            if (profile == null) {
                mutableState.value = TailscaleStatusUiState(failed = true)
                return@launch
            }
            profileName = profile.displayName()
            val owner = TailscaleStatusSession(TailscaleStatusClient(getApplication()), profile.id, profile.uuid)
            session = owner
            if (foreground) owner.foreground()
            owner.state.collect { mutableState.value = it }
        }
    }

    internal fun foreground() {
        foreground = true
        session?.foreground()
    }

    internal fun background(changingConfiguration: Boolean) {
        foreground = changingConfiguration
        session?.background(changingConfiguration)
    }

    override fun onCleared() {
        session?.close()
    }
}
