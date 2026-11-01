package xyz.nekobyte.nekobox.ui

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.ktx.PreferenceProxy

/**
 * Each Settings page loads its file, and AndroidX then saves each XML default the store lacks.
 * Those writes must not change a value the app reads from an empty store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsDefaultsTest {

    // PreferenceManager inflation is library-restricted API; SettingsPreferenceFragment reaches it
    // through setPreferencesFromResource.
    @SuppressLint("RestrictedApi")
    @Test
    fun openingSettingsKeepsTheValuesTheAppReads() {
        ConfigBuilderTestEnv.reset()
        runBlocking { withContext(Dispatchers.IO) { DataStore.configurationStore.replaceAllDurable(emptyList()) } }
        val settings = settingReaders()
        assertTrue(settings.containsKey("enableDnsRouting"))
        val before = settings.mapValues { (_, read) -> read() }

        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_NekoBox)
        for (file in SettingsPages.files) {
            PreferenceManager(context).apply {
                preferenceDataStore = DataStore.configurationStore
                inflateFromResource(context, file, null)
            }
        }

        val changed = settings.filter { (key, read) -> read() != before[key] }
            .mapValues { (key, read) -> before[key] to read() }
        assertEquals(emptyMap<String, Pair<Any?, Any?>>(), changed)
    }

    /** DataStore properties stored under a key of global_preferences.xml, read the way the app reads them. */
    private fun settingReaders(): Map<String, () -> Any?> {
        val keys = xmlKeys()
        return DataStore::class.java.declaredFields
            .filter { it.type == PreferenceProxy::class.java }
            .mapNotNull { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val proxy = field.get(DataStore) as PreferenceProxy<Any?>
                proxy.takeIf { it.name in keys }?.let { found ->
                    found.name to { found.getter(found.name, found.defaultValue()) }
                }
            }
            .toMap()
    }

    private fun xmlKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        for (file in SettingsPages.files) {
            val parser = RuntimeEnvironment.getApplication().resources.getXml(file)
            try {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        parser.getAttributeValue(APP_NAMESPACE, "key")?.let(keys::add)
                    }
                    parser.next()
                }
            } finally {
                parser.close()
            }
        }
        return keys
    }

    private companion object {
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
