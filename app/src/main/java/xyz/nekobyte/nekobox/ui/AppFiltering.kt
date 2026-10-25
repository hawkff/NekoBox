package xyz.nekobyte.nekobox.ui

import androidx.core.view.isVisible
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.LayoutAppPlaceholderBinding

internal interface AppFilterEntry {
    val name: CharSequence
    val packageName: String
    val uid: Int
    val sys: Boolean
}

internal fun <T : AppFilterEntry> filterApps(apps: List<T>, query: CharSequence, includeSystem: Boolean) = apps.filter { app ->
    val matchesQuery = query.isEmpty() ||
        app.name.contains(query, ignoreCase = true) ||
        app.packageName.contains(query, ignoreCase = true) ||
        app.uid.toString().contains(query)
    matchesQuery && (includeSystem || !app.sys)
}

/** Says why a loaded app list shows nothing: the search matches no app, or only system apps are left and they are hidden. */
internal fun LayoutAppPlaceholderBinding.showNoMatch(empty: Boolean, query: CharSequence, systemHidden: Boolean) {
    root.isVisible = empty
    if (!empty) return
    openSettings.isVisible = false
    val context = root.context
    val reason = if (query.isEmpty()) context.getString(R.string.app_list_empty) else context.getString(R.string.app_list_no_match, query)
    emptyMessage.text = if (systemHidden) reason + "\n" + context.getString(R.string.app_list_system_hidden) else reason
}

/** The other use of the placeholder: the installed apps cannot be read at all. */
internal fun LayoutAppPlaceholderBinding.showUnreadable() {
    emptyMessage.setText(R.string.app_list_permission_denied)
    openSettings.isVisible = true
}
