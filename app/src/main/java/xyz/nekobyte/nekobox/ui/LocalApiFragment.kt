package xyz.nekobyte.nekobox.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.api.LocalApiAccess
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.widget.ListListener

/** API control: the local API's switch, its token, and what other apps can see of it. */
class LocalApiFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_api)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction().replace(R.id.settings, LocalApiPreferences()).commit()
        }
    }
}

class LocalApiPreferences : PreferenceFragmentCompat() {

    private companion object {
        val saving = Mutex()
    }

    private lateinit var enabled: SwitchPreference
    private lateinit var copyToken: Preference
    private lateinit var replaceToken: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.local_api_preferences, rootKey)
        enabled = findPreference("localApiEnabled")!!
        copyToken = findPreference("localApiCopyToken")!!
        replaceToken = findPreference("localApiReplaceToken")!!
        // Turning the API on gives it a new token and port; off keeps them until then.
        enabled.setOnPreferenceChangeListener { _, on ->
            save { current -> if (on == true) LocalApiAccess.fresh() else current?.copy(enabled = false) }
            false
        }
        // The stored token, not the one shown: a script may have replaced it since.
        copyToken.setOnPreferenceClickListener {
            val app = requireContext().applicationContext
            runOnDefaultDispatcher {
                val current = runCatching { LocalApiAccess.read(app) }.getOrNull()?.takeIf { it.enabled }
                onMainDispatcher {
                    if (!isAdded || current == null) return@onMainDispatcher
                    LocalApiAccess.copyToken(requireContext(), current.token)
                    // From Android 13 the system confirms a copy itself.
                    if (Build.VERSION.SDK_INT < 33) snackbar(R.string.local_api_token_copied).show()
                }
            }
            true
        }
        replaceToken.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.local_api_rotate)
                .setMessage(R.string.local_api_rotate_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_api_rotate) { _, _ ->
                    save { current -> current?.copy(token = LocalApiAccess.token()) }
                }
                .show()
            true
        }
    }

    // The notification can turn the API off while this screen is away.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Saves what [change] makes of the stored settings, one change at a time, then shows the result. */
    private fun save(change: (LocalApiAccess.Config?) -> LocalApiAccess.Config?) {
        val activity = requireActivity()
        runOnDefaultDispatcher {
            val made = saving.withLock {
                // An unreadable file reads as none, so turning the API on replaces it.
                runCatching { change(runCatching { LocalApiAccess.read(activity) }.getOrNull()) }.onSuccess { next ->
                    next?.let { LocalApiAccess.saveFromUi(activity, it).join() }
                }.isSuccess
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                if (!made) Toast.makeText(activity, R.string.local_api_start_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun refresh() {
        val app = requireContext().applicationContext
        runOnDefaultDispatcher {
            val current = runCatching { LocalApiAccess.read(app) }.getOrNull()
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                val on = current?.takeIf { it.enabled }
                enabled.isChecked = on != null
                enabled.summary = on?.let { getString(R.string.local_api_listening, it.port) } ?: getString(R.string.local_api_summary)
                copyToken.isVisible = on != null
                replaceToken.isVisible = on != null
            }
        }
    }
}
