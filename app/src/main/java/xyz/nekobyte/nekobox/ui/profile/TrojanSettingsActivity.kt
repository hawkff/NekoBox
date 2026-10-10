package xyz.nekobyte.nekobox.ui.profile

import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean

class TrojanSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = TrojanBean()
}
