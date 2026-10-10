package xyz.nekobyte.nekobox.ui.profile

import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = VMessBean().apply {
        if (intent?.getBooleanExtra("vless", false) == true) {
            alterId = -1
        }
        initializeDefaultValues()
    }
}
