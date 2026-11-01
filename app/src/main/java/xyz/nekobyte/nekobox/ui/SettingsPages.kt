package xyz.nekobyte.nekobox.ui

import xyz.nekobyte.nekobox.R

/** The Settings pages, each in its own file, by the key of its row on the hub (global_preferences.xml). */
internal object SettingsPages {
    val byKey = mapOf(
        "screen_appearance" to R.xml.settings_appearance,
        "screen_connection" to R.xml.settings_connection,
        "screen_protection" to R.xml.settings_protection,
        "screen_route" to R.xml.settings_route,
        "screen_dns" to R.xml.settings_dns,
        "screen_inbound" to R.xml.settings_inbound,
        "screen_tls" to R.xml.settings_tls,
        "screen_notification" to R.xml.settings_notification,
        "screen_advanced" to R.xml.settings_advanced,
    )

    /** Every file that holds settings: the hub and its pages. */
    val files = listOf(R.xml.global_preferences) + byKey.values
}
