package xyz.nekobyte.nekobox.fmt

import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R

enum class PluginEntry(val pluginId: String, val displayName: String) {
    MieruProxy("mieru-plugin", NekoBox.application.getString(R.string.action_mieru)),
    NaiveProxy("naive-plugin", NekoBox.application.getString(R.string.action_naive)),
    Hysteria("hysteria-plugin", NekoBox.application.getString(R.string.action_hysteria)),
    ;

    companion object {
        fun find(name: String) = entries.firstOrNull { it.pluginId == name }
    }
}
