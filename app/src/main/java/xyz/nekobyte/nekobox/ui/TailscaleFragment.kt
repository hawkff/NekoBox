package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.ui.profile.TailscaleSettingsActivity
import xyz.nekobyte.nekobox.widget.ListListener

/** The Tailscale nodes of every group, each with its status and settings screens. */
class TailscaleFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.tailscale)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction().replace(R.id.settings, NodesFragment()).commit()
        }
    }

    class NodesFragment : PreferenceFragmentCompat() {

        private var loading: Job? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        }

        override fun onResume() {
            super.onResume()
            loading?.cancel()
            loading = lifecycleScope.launch {
                val (nodes, subscriptions) = withContext(Dispatchers.IO) {
                    val nodes = ProfileDatabase.proxyDao.run { getEntities(getIdsByType(ProxyEntity.TYPE_TAILSCALE)) }
                    nodes to ProfileDatabase.groupDao.subscriptions().map { it.id }.toSet()
                }
                show(nodes, subscriptions)
            }
        }

        private fun show(nodes: List<ProxyEntity>, subscriptions: Set<Long>) {
            preferenceScreen.removeAll()
            for (node in nodes) {
                val bean = node.tailscaleBean ?: continue
                val category = PreferenceCategory(requireContext()).apply {
                    title = node.displayName()
                    isIconSpaceReserved = false
                }
                preferenceScreen.addPreference(category)
                category.addPreference(
                    row(R.string.tailscale_node_status, bean.controlUrl?.ifBlank { null } ?: DEFAULT_CONTROL_SERVER) {
                        Intent(it, TailscaleStatusActivity::class.java).putExtra(TailscaleStatusActivity.EXTRA_PROFILE_ID, node.id)
                    },
                )
                category.addPreference(
                    row(R.string.settings, null) {
                        // As in the profile list, the running profile cannot be edited or deleted.
                        if (DataStore.serviceState.started && DataStore.currentProfile == node.id) {
                            Toast.makeText(it, R.string.tailscale_node_in_use, Toast.LENGTH_SHORT).show()
                            null
                        } else {
                            node.settingIntent(it, node.groupId in subscriptions)
                        }
                    },
                )
            }
            preferenceScreen.addPreference(row(R.string.tailscale_add_node, null) { Intent(it, TailscaleSettingsActivity::class.java) })
        }

        private fun row(title: Int, summary: String?, intent: (Context) -> Intent?) = Preference(requireContext()).apply {
            setTitle(title)
            this.summary = summary
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                intent(context)?.let(::startActivity)
                true
            }
        }
    }

    private companion object {
        const val DEFAULT_CONTROL_SERVER = "controlplane.tailscale.com"
    }
}
