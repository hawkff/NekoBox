package xyz.nekobyte.nekobox.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ktx.alert
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.widget.ListListener
import java.text.DateFormat
import java.util.Date

/** One Nebula network, joined alongside the selected profile: its config and whether to join it. */
class NebulaFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.nebula)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction().replace(R.id.settings, NetworkFragment()).commit()
        }
    }

    class NetworkFragment : PreferenceFragmentCompat() {

        private lateinit var enabled: SwitchPreference
        private lateinit var config: Preference
        private lateinit var networks: Preference

        private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch { import(uri) }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.configurationStore
            val context = requireContext()
            enabled = SwitchPreference(context).apply {
                key = Key.NEBULA_ENABLED
                setTitle(R.string.nebula_enabled)
                setSummary(R.string.nebula_enabled_summary)
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, _ ->
                    needReload()
                    true
                }
            }
            config = Preference(context).apply {
                setTitle(R.string.nebula_config)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    // YAML has no registered type; the picker shows every file.
                    this@NetworkFragment.import.launch(arrayOf("*/*"))
                    true
                }
            }
            networks = Preference(context).apply {
                setTitle(R.string.nebula_networks)
                isPersistent = false
                isSelectable = false
                isIconSpaceReserved = false
            }
            preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
                addPreference(enabled)
                addPreference(config)
                addPreference(networks)
            }
            show()
        }

        private fun show() {
            val info = DataStore.nebulaConfig.takeIf { it.isNotBlank() }?.let { runCatching { Libcore.readNebulaConfig(it) }.getOrNull() }
            config.summary = info?.let {
                val expires = it.expires * 1000
                getString(
                    if (expires < System.currentTimeMillis()) R.string.nebula_config_expired else R.string.nebula_config_summary,
                    it.name,
                    it.addresses.lines().first(),
                    DateFormat.getDateInstance().format(Date(expires)),
                )
            } ?: getString(R.string.nebula_config_none)
            networks.isVisible = info != null
            networks.summary = info?.networks?.lines()?.joinToString(", ")
            // A config that stopped reading, such as one set through the API, can still be turned off.
            enabled.isEnabled = info != null || enabled.isChecked
        }

        private suspend fun import(uri: Uri) {
            val resolver = requireContext().contentResolver
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ByteArray(MAX_CONFIG_BYTES + 1)
                    val size = resolver.openInputStream(uri)!!.use { input ->
                        var read = 0
                        while (read < bytes.size) read += input.read(bytes, read, bytes.size - read).takeIf { it >= 0 } ?: break
                        read
                    }
                    require(size <= MAX_CONFIG_BYTES) { getString(R.string.nebula_config_too_big) }
                    bytes.decodeToString(0, size).also { Libcore.readNebulaConfig(it) }
                }
            }
            if (!isAdded) return
            result.onSuccess {
                DataStore.nebulaConfig = it
                // Importing a config means using it.
                enabled.isChecked = true
                show()
                needReload()
            }.onFailure {
                alert(it.readableMessage).apply { setTitle(R.string.nebula_config_unusable) }.show()
            }
        }
    }

    private companion object {
        // A config with its PKI inline is a few kilobytes; this bounds what is read into memory.
        const val MAX_CONFIG_BYTES = 256 * 1024
    }
}
