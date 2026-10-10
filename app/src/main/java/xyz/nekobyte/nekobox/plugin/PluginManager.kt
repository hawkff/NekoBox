package xyz.nekobyte.nekobox.plugin

import android.content.pm.ComponentInfo
import android.content.pm.ProviderInfo
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File
import java.io.FileNotFoundException

object PluginManager {

    class PluginNotFoundException(val plugin: String) :
        FileNotFoundException(plugin),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = NekoBox.application.getString(R.string.plugin_unknown, plugin)
    }

    data class InitResult(val path: String)

    @Throws(Throwable::class)
    fun init(pluginId: String): InitResult? {
        if (pluginId.isEmpty()) return null
        var throwable: Throwable? = null

        try {
            val result = initNative(pluginId)
            if (result != null) return result
        } catch (t: Throwable) {
            throwable = t
            Logs.w(t)
        }

        throw throwable ?: PluginNotFoundException(pluginId)
    }

    private fun initNative(pluginId: String): InitResult? {
        // A bundled sidecar always wins over an externally-installed provider. External
        // providers are signer-checked below, but they must not shadow binaries we ship.
        try {
            initNativeInternal(pluginId)?.let { path ->
                return InitResult(path)
            }
        } catch (t: Throwable) {
            Logs.w("initNativeInternal failed", t)
        }

        val info = Plugins.getPluginExternal(pluginId) ?: return null
        try {
            initNativeFaster(info)?.let { return InitResult(it) }
        } catch (t: Throwable) {
            Logs.w("initNativeFaster failed", t)
        }

        Logs.w("Init native returns empty result")
        return null
    }

    private fun initNativeInternal(pluginId: String): String? {
        fun soIfExist(soName: String): String? {
            val f = File(NekoBox.application.applicationInfo.nativeLibraryDir, soName)
            if (f.canExecute()) {
                return f.absolutePath
            }
            return null
        }
        return when (pluginId) {
            "mieru-plugin" -> soIfExist("libmieru.so")
            "naive-plugin" -> soIfExist("libnaive.so")
            else -> null
        }
    }

    private fun initNativeFaster(provider: ProviderInfo): String? = provider.loadString(Plugins.METADATA_KEY_EXECUTABLE_PATH)
        ?.let { relativePath ->
            File(provider.applicationInfo.nativeLibraryDir).resolve(relativePath).apply {
                check(canExecute())
            }.absolutePath
        }

    fun ComponentInfo.loadString(key: String) = when (val value = metaData?.get(key)) {
        is String -> value

        is Int -> NekoBox.application.packageManager.getResourcesForApplication(applicationInfo)
            .getString(value)

        null -> null

        else -> error("meta-data $key has invalid type ${value.javaClass}")
    }
}
