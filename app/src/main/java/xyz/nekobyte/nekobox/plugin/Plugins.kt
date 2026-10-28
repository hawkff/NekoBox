package xyz.nekobyte.nekobox.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.plugin.PluginManager.loadString
import xyz.nekobyte.nekobox.utils.PackageCache

object Plugins {
    const val CONTRACT_NAMESPACE = "xyz.nekobyte.nekobox.plugin"
    const val ACTION_NATIVE_PLUGIN = "$CONTRACT_NAMESPACE.ACTION_NATIVE_PLUGIN"
    const val METADATA_KEY_ID = "$CONTRACT_NAMESPACE.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "$CONTRACT_NAMESPACE.executable_path"

    private val rejectionDeduplicator = BoundedDeduplicator()
    private val hostCurrentFingerprints by lazy {
        PluginTrust.readSignerRecord(
            NekoBox.application.packageManager,
            NekoBox.application.packageName,
        )?.current.orEmpty()
    }

    private fun isPluginProvider(provider: ProviderInfo) = provider.exported && provider.enabled &&
        provider.authority?.startsWith("$CONTRACT_NAMESPACE.") == true

    fun providers(pkg: PackageInfo) = pkg.providers.orEmpty().filter(::isPluginProvider)

    internal fun matchesPlugin(provider: ProviderInfo, pluginId: String): Boolean {
        if (!isPluginProvider(provider) || pluginId.isBlank()) return false
        return try {
            provider.loadString(METADATA_KEY_ID) == pluginId &&
                !provider.loadString(METADATA_KEY_EXECUTABLE_PATH).isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null
        val result = evaluateProviders(discoverExternal(pluginId))
        notifyRejected(pluginId, result.rejected)
        val providers = result.trusted.sortedWith(compareBy({ it.packageName }, { it.name }))
        if (providers.size > 1) {
            val message = "Conflicting plugins found from: ${providers.joinToString { it.packageName }}"
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(NekoBox.application, message, Toast.LENGTH_LONG).show()
            }
        }
        return providers.firstOrNull()
    }

    fun inspectRejectedPlugins(pluginId: String): List<PluginTrust.RejectedPlugin> {
        if (pluginId.isBlank()) return emptyList()
        return evaluateProviders(discoverExternal(pluginId)).rejected.distinctBy {
            PluginTrust.rejectionKey(pluginId, it)
        }
    }

    private fun discoverExternal(pluginId: String): List<ProviderInfo> {
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        val uri = Uri.Builder().scheme("plugin").authority(CONTRACT_NAMESPACE).path("/$pluginId").build()
        val queried = NekoBox.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, uri),
            flags,
        ).mapNotNull { it.providerInfo }.filter { matchesPlugin(it, pluginId) }
        if (queried.isNotEmpty()) return queried

        PackageCache.awaitLoadSync()
        return PackageCache.installedPluginPackages.values.flatMap(::providers).filter { matchesPlugin(it, pluginId) }
    }

    private fun evaluateProviders(providers: List<ProviderInfo>) = PluginTrust.filterTrustedCandidates(
        candidates = providers,
        packageName = { it.packageName },
        signerRecord = {
            PluginTrust.readSignerRecord(NekoBox.application.packageManager, it.packageName)
        },
        hostCurrentFingerprints = hostCurrentFingerprints,
        approvals = DataStore.pluginSignerApprovals,
    )

    private fun notifyRejected(pluginId: String, rejected: List<PluginTrust.RejectedPlugin>) {
        rejected.distinctBy { PluginTrust.rejectionKey(pluginId, it) }.forEach { rejection ->
            val key = PluginTrust.rejectionKey(pluginId, rejection)
            if (!rejectionDeduplicator.shouldNotify(key)) return@forEach
            val signer = rejection.currentFingerprints?.sorted()?.firstOrNull()?.take(16)
                ?: NekoBox.application.getString(R.string.plugin_signer_unreadable)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    NekoBox.application,
                    NekoBox.application.getString(
                        R.string.plugin_signer_rejected,
                        rejection.packageName,
                        signer,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
