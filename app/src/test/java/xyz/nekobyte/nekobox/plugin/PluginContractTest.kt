package xyz.nekobyte.nekobox.plugin

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.utils.PackageCache
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginContractTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    private fun provider() = ProviderInfo().apply {
        name = "io.example.plugin.Provider"
        packageName = "io.example.plugin"
        authority = "${Plugins.CONTRACT_NAMESPACE}.fixture"
        exported = true
        enabled = true
        applicationInfo = ApplicationInfo().apply {
            packageName = "io.example.plugin"
            nativeLibraryDir = folder.root.path
            enabled = true
        }
        metaData = Bundle().apply {
            putString(Plugins.METADATA_KEY_ID, "hysteria-plugin")
            putString(Plugins.METADATA_KEY_EXECUTABLE_PATH, "libhysteria.so")
        }
    }

    @Test
    fun requiresTheCurrentAuthorityAndMetadata() {
        val provider = provider()
        assertTrue(Plugins.matchesPlugin(provider, "hysteria-plugin"))
        assertFalse(Plugins.matchesPlugin(provider, "naive-plugin"))
        assertFalse(Plugins.matchesPlugin(provider, ""))
        provider.authority = "io.example.previous.plugin"
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
        provider.authority = "${Plugins.CONTRACT_NAMESPACE}.fixture"
        provider.metaData = Bundle().apply { putString("io.example.previous.id", "hysteria-plugin") }
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
        provider.metaData = null
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
    }

    @Test
    fun rejectsDisabledPrivateAndMalformedProviders() {
        val provider = provider()
        provider.exported = false
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
        provider.exported = true
        provider.enabled = false
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
        provider.enabled = true
        provider.metaData.putBoolean(Plugins.METADATA_KEY_ID, true)
        assertFalse(Plugins.matchesPlugin(provider, "hysteria-plugin"))
    }

    @Test
    fun findsProvidersAfterUnrelatedProvidersInTheSamePackage() {
        val supported = provider()
        val pkg = PackageInfo().apply { providers = arrayOf(ProviderInfo(), supported) }
        assertEquals(listOf(supported), Plugins.providers(pkg))
    }

    @Test
    fun currentContractStillRequiresSignerApprovalBeforeExecution() {
        val provider = provider()
        val signer = "fixture signer".toByteArray()
        val pkg = PackageInfo().apply {
            packageName = provider.packageName
            applicationInfo = provider.applicationInfo
            providers = arrayOf(ProviderInfo(), provider)
            signatures = arrayOf(Signature(signer))
        }
        shadowOf(NekoBox.application.packageManager).installPackage(pkg)
        PackageCache.reload()
        assertNull(Plugins.getPluginExternal("hysteria-plugin"))
        val rejected = Plugins.inspectRejectedPlugins("hysteria-plugin").single()
        assertEquals(pkg.packageName, rejected.packageName)
        runBlocking {
            DataStore.approvePluginSigner(
                checkNotNull(PluginTrust.approvalIdentity(pkg.packageName, setOf(PluginTrust.sha256Hex(signer)))),
            )
        }
        assertNotNull(Plugins.getPluginExternal("hysteria-plugin"))
        val binary = folder.newFile("libhysteria.so").apply { setExecutable(true) }
        assertEquals(binary.path, PluginManager.init("hysteria-plugin")?.path)
    }

    @Test
    fun intentDiscoveryUsesOnlyTheCurrentContract() {
        val provider = provider()
        val intent = Intent(
            Plugins.ACTION_NATIVE_PLUGIN,
            Uri.Builder().scheme("plugin").authority(Plugins.CONTRACT_NAMESPACE).path("/hysteria-plugin").build(),
        )
        shadowOf(NekoBox.application.packageManager).addResolveInfoForIntent(
            intent,
            ResolveInfo().apply { providerInfo = provider },
        )
        assertEquals(provider.packageName, Plugins.inspectRejectedPlugins("hysteria-plugin").single().packageName)
    }

    @Test
    fun bundledExecutablesDoNotNeedAnExternalPlugin() {
        val original = NekoBox.application.applicationInfo.nativeLibraryDir
        NekoBox.application.applicationInfo.nativeLibraryDir = folder.root.path
        try {
            for ((id, filename) in mapOf("mieru-plugin" to "libmieru.so", "naive-plugin" to "libnaive.so")) {
                val binary = folder.newFile(filename).apply { setExecutable(true) }
                assertEquals(binary.path, PluginManager.init(id)?.path)
            }
        } finally {
            NekoBox.application.applicationInfo.nativeLibraryDir = original
        }
    }

    @Test
    fun manifestQueriesTheSameActionAsDiscovery() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val actions = queries.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals(
            Plugins.ACTION_NATIVE_PLUGIN,
            (actions.item(0) as org.w3c.dom.Element).getAttribute("android:name"),
        )
    }
}
