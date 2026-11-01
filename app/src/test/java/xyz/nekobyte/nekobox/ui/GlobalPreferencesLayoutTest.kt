package xyz.nekobyte.nekobox.ui

import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import xyz.nekobyte.nekobox.R

/**
 * global_preferences.xml is the Settings hub: one keyed, titled, summarized row per page, each opening its page from its
 * own file (SettingsPages). This guards the shape navigation relies on: every row has its page and no more, a page
 * holds no nested screens, and moving items between pages never drops a setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GlobalPreferencesLayoutTest {

    @Test
    fun hubRowsOpenTheirPagesAndNoSettingIsLost() {
        val screens = mutableListOf<String>()
        read(R.xml.global_preferences) { depth, parser ->
            val key = parser.getAttributeValue(APP_NAMESPACE, "key")
            if (depth == 1) return@read assertEquals("PreferenceScreen", parser.name)
            assertEquals("hub row $key holds preferences; they belong in its page", 2, depth)
            assertEquals("hub row $key", "PreferenceScreen", parser.name)
            assertTrue("hub row without key", key.orEmpty().startsWith("screen_"))
            for (attribute in listOf("title", "summary", "icon")) {
                assertTrue("hub row $key lacks $attribute", parser.getAttributeResourceValue(APP_NAMESPACE, attribute, 0) != 0)
            }
            assertEquals("hub row $key", SettingsPreferenceFragment::class.java.name, parser.getAttributeValue(APP_NAMESPACE, "fragment"))
            screens += requireNotNull(key)
        }
        assertEquals(SettingsPages.byKey.keys.toList(), screens)

        val leafKeys = mutableListOf<String>()
        for (page in SettingsPages.byKey.values) {
            read(page) { depth, parser ->
                when {
                    depth == 1 -> assertEquals("PreferenceScreen", parser.name)

                    parser.name != "PreferenceCategory" -> {
                        assertFalse("nested screens in a page", parser.name == "PreferenceScreen")
                        leafKeys += requireNotNull(parser.getAttributeValue(APP_NAMESPACE, "key")) { "${parser.name} without key at depth $depth" }
                    }
                }
            }
        }
        assertEquals(leafKeys.sorted(), leafKeys.distinct().sorted())
        assertEquals(EXPECTED_KEYS, leafKeys.toSet())
    }

    /** Calls [element] for every start tag of [file], with its depth: 1 for the root. */
    private fun read(file: Int, element: (Int, XmlResourceParser) -> Unit) {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(file)
        var depth = 0
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> element(++depth, parser)
                    XmlPullParser.END_TAG -> depth--
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
    }

    private companion object {
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"

        val EXPECTED_KEYS = setOf(
            "acquireWakeLock", "allowAccess", "allowInsecureOnRequest", "alwaysShowAddress", "appLanguage",
            "appTLSVersion", "appTheme", "appendHttpProxy", "bypassLan", "bypassLanInCore", "clearCache",
            "confirmProfileDelete", "connectionTestURL", "directDns", "dnsHosts",
            "domain_strategy_for_direct", "domain_strategy_for_remote", "domain_strategy_for_server",
            "enableDnsRouting", "enableFakeDns", "enableTLSFragment",
            "globalAllowInsecure", "globalCustomConfig", "gpsCoordinates", "gpsLookupUrl",
            "gpsSpoofing", "gpsSpoofingNotice", "gpsSpoofingRemove", "helpToImprove", "hideFromRecentApps",
            "httpProxyBypass", "ipv6Mode", "isAutoConnect", "killSwitch", "liquidGlass", "logBufSize", "logLevel", "meteredNetwork", "mixedPort", "mtu",
            "networkAutomation", "networkAutomationRules", "networkChangeResetConnections", "nightTheme", "profileTrafficStatistics", "protectionAlwaysOn",
            "protectionBattery", "protectionLockdown", "protectionNotes", "proxyApps", "proxyModeInboundAuth", "remoteDns", "requireProxyInVPN", "resetSettings", "resolveDestination",
            "rulesGeoipUrl", "rulesGeositeUrl", "rulesProvider", "rulesUpdateInterval", "serviceMode",
            "showBottomBar", "showDirectSpeed", "showGroupInNotification", "speedInterval",
            "trafficSniffing", "tunImplementation", "wakeResetConnections", "webrtcLeakProtection",
        )
    }
}
