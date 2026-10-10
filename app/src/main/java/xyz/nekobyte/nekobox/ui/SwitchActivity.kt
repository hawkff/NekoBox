package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher

class SwitchActivity :
    ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, null, R.string.action_switch),
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        // Carry the freshly-selected id in the reload IPC so :bg uses it authoritatively rather
        // than re-reading selectedProxy from the DB before the async write-through commit lands.
        NekoBox.reloadService(profileId)
        finish()
    }
}
