package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.unwrap

/** Settings > Appearance > App icon: a picker of [LauncherIcon]s with their previews; a change asks first. */
class LauncherIconPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init {
        isPersistent = false
        widgetLayoutResource = R.layout.preference_widget_launcher_icon
        summaryProvider = SummaryProvider<LauncherIconPreference> { context.getString(LauncherIcon.current(context).label) }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.launcher_icon_preview) as ImageView).setImageResource(LauncherIcon.current(context).icon)
    }

    override fun onClick() {
        val icons = LauncherIcon.entries
        val current = LauncherIcon.current(context)
        val rows = icons.map { context.getString(it.label) to it.summary?.let(context::getString) }
        val previews = icons.map { AppCompatResources.getDrawable(context, it.icon)!! }
        showChoiceDialog(context, title, rows, icons.indexOf(current), previews) { index ->
            val picked = icons[index]
            // Picking the icon shown again puts every alias back as it should be.
            if (picked != current) confirm(picked) else apply(picked)
        }
    }

    private fun confirm(picked: LauncherIcon) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.launcher_icon_change_title)
            .setMessage(R.string.launcher_icon_change_message)
            .setPositiveButton(R.string.launcher_icon_change) { _, _ -> apply(picked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Off the main thread: a switch waits for the one under way, or for the reconciliation at start.
    private fun apply(picked: LauncherIcon) {
        val app = context.applicationContext
        runOnDefaultDispatcher {
            val switched = picked.trySelect(app)
            onMainDispatcher {
                if (!switched) context.unwrap<ThemedActivity>().snackbar(R.string.launcher_icon_change_failed).show()
                notifyChanged()
            }
            // Pinned profile shortcuts name the launcher entry they belong to.
            migratePinnedProfileShortcuts(app)
        }
    }
}
