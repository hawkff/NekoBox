package xyz.nekobyte.nekobox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import libcore.Libcore
import org.json.JSONObject
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.databinding.LayoutAboutBinding
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.isPreview
import xyz.nekobyte.nekobox.ktx.launchCustomTab
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnIoDispatcher
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher
import xyz.nekobyte.nekobox.plugin.PluginManager.loadString
import xyz.nekobyte.nekobox.plugin.Plugins
import xyz.nekobyte.nekobox.utils.PackageCache
import xyz.nekobyte.nekobox.widget.CheckCat
import xyz.nekobyte.nekobox.widget.ListListener

/**
 * About, as a colophon: the name and version, the parts the app runs on, background use, the update
 * check and the project's pages, and the cat by its ramen closing the page.
 */
class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    private var _binding: LayoutAboutBinding? = null
    private val binding get() = _binding!!

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The battery screens report RESULT_CANCELED even when the exemption was granted: read it again.
        if (_binding != null) showBackground()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutAboutBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.edition.text = getString(R.string.about_edition, NekoBox.appVersionNameForDisplay)
        // sing-box and the Go it was built with; the build tags stay out of a colophon. When the value
        // wraps, the separator moves down with the Go version instead of ending the first line.
        val core = Libcore.versionBox().lines()
        binding.core.text = listOfNotNull(core.firstOrNull()?.replace("sing-box: ", "sing-box "), core.getOrNull(1)?.substringBefore('@')).joinToString(" ·\u00A0")
        binding.cat.pose = CheckCat.Pose.CONTENT
        binding.background.setOnClickListener { askForBackgroundUse() }
        binding.allowBackground.setOnClickListener { askForBackgroundUse() }
        binding.checkUpdates.setOnClickListener { checkUpdate() }
        for ((page, link) in listOf(binding.source to SOURCE, binding.releases to "$SOURCE/releases", binding.license to "$SOURCE/blob/main/LICENSE")) {
            page.contentDescription = getString(R.string.about_opens_browser, page.text)
            page.setOnClickListener { it.context.launchCustomTab(link) }
        }
        showBackground()
        showPlugins()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun ignoringBatteryOptimizations() = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)

    private fun showBackground() {
        val allowed = ignoringBatteryOptimizations()
        binding.background.setText(if (allowed) R.string.about_background_allowed else R.string.about_background_restricted)
        // Restricted, the button asks; the line only explains. Allowed, the line opens the system list to change it.
        binding.background.isClickable = allowed
        binding.background.isFocusable = allowed
        binding.allowBackground.isVisible = !allowed
    }

    // Persistent connections need the exemption, which the user grants and revokes in the system's screens.
    @SuppressLint("BatteryLife")
    private fun askForBackgroundUse() {
        val intent = if (ignoringBatteryOptimizations()) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${app.packageName}".toUri())
        }
        requestIgnoreBatteryOptimizations.launch(intent)
    }

    // Reading the installed plugins can wait for the package list, so they fill in when ready.
    private fun showPlugins() = runOnIoDispatcher {
        PackageCache.awaitLoadSync()
        val plugins = PackageCache.installedPluginPackages.values.mapNotNull { pkg ->
            runCatching { Plugins.providers(pkg).firstOrNull()?.loadString(Plugins.METADATA_KEY_ID) }
                .onFailure { Logs.w(it) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { "$it ${pkg.versionName}" }
        }
        runOnMainDispatcher {
            val binding = _binding ?: return@runOnMainDispatcher
            binding.pluginsRow.isVisible = plugins.isNotEmpty()
            binding.plugins.text = plugins.joinToString("\n")
        }
    }

    private fun checkUpdate() {
        binding.checkUpdates.isEnabled = false
        // The icon turns into a spinner while the check runs, so the tap shows it did something.
        binding.checkUpdates.icon = requireContext().let { context ->
            IndeterminateDrawable.createCircularDrawable(
                context,
                CircularProgressIndicatorSpec(context, null, 0, com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall),
            )
        }
        runOnIoDispatcher {
            val found = try {
                val client = Libcore.newHttpClient().apply {
                    modernTLS()
                    trySocks5(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
                }
                val response = client.newRequest().apply { setURL(LATEST) }.execute()
                val release = JSONObject(response.getContentStringLimited(10L * 1024 * 1024))
                val name = release.getString("name")
                // A preview build is behind any release of another name; a release build, behind one without its version.
                val newer = name.isNotBlank() && if (isPreview) name != BuildConfig.VERSION_NAME else !name.contains(BuildConfig.VERSION_NAME)
                Result.success(if (newer) name to release.getString("html_url") else null)
            } catch (e: Exception) {
                Logs.w(e)
                Result.failure(e)
            }
            runOnMainDispatcher {
                // The check may outlive the page.
                val binding = _binding ?: return@runOnMainDispatcher
                binding.checkUpdates.isEnabled = true
                binding.checkUpdates.setIconResource(R.drawable.ic_baseline_update_24)
                found.onFailure { Toast.makeText(app, it.readableMessage, Toast.LENGTH_SHORT).show() }
                found.onSuccess { update ->
                    if (update == null) return@onSuccess Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                    val (name, url) = update
                    val context = requireContext()
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_dialog_title)
                        .setMessage(context.getString(R.string.update_dialog_message, NekoBox.appVersionNameForDisplay, name))
                        .setPositiveButton(R.string.yes) { _, _ -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            }
        }
    }

    private companion object {
        const val SOURCE = "https://github.com/hawkff/NekoBox"
        const val LATEST = "https://api.github.com/repos/hawkff/NekoBox/releases/latest"
    }
}
