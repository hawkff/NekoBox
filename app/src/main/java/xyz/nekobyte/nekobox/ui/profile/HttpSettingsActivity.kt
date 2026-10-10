package xyz.nekobyte.nekobox.ui.profile

import xyz.nekobyte.nekobox.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = HttpBean()
}
