package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

// Rows read these maps on every bind. A refreshed settings store hands out an equal text as a new object, and comparing
// tens of kilobytes on each read froze lists of hundreds of proxies.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DataStoreParsedMapsTest {
    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    // What a refresh does: the same text, in a new object.
    private fun refresh(key: String) {
        val raw = DataStore.configurationStore.getString(key)!!
        DataStore.configurationStore.putString(key, String(raw.toCharArray()))
    }

    @Test
    fun aNewCopyOfTheExits_keepsTheParsedMap_andAChangeIsRead() {
        DataStore.rememberProxyExits(mapOf(1L to "203.0.113.7"))
        val parsed = DataStore.proxyExits
        refresh(Key.PROXY_EXITS)
        assertSame(parsed, DataStore.proxyExits)
        // The newer copy is kept, so the next read is an identity check, not another comparison of the whole text.
        assertSame(DataStore.configurationStore.getString(Key.PROXY_EXITS), DataStore.parsedProxyExits.first)
        assertSame(parsed, DataStore.proxyExits)
        DataStore.rememberProxyExits(mapOf(2L to "203.0.113.8"))
        assertEquals(mapOf(1L to "203.0.113.7", 2L to "203.0.113.8"), DataStore.proxyExits)
    }

    @Test
    fun aNewCopyOfTheServerAddresses_keepsTheParsedMap_andAChangeIsRead() {
        DataStore.rememberServerAddresses(mapOf("node.example" to "192.0.2.1"))
        val parsed = DataStore.serverAddresses
        refresh(Key.SERVER_ADDRESSES)
        assertSame(parsed, DataStore.serverAddresses)
        assertSame(DataStore.configurationStore.getString(Key.SERVER_ADDRESSES), DataStore.parsedServerAddresses.first)
        DataStore.rememberServerAddresses(mapOf("other.example" to "192.0.2.2"))
        assertEquals(mapOf("node.example" to "192.0.2.1", "other.example" to "192.0.2.2"), DataStore.serverAddresses)
    }
}
