package xyz.nekobyte.nekobox.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // A check of a list asks about every server, and each change writes the whole text again: a name that still
    // resolves to the address it has writes nothing.
    @Test
    fun aNameThatKeepsItsAddress_keepsItsEntry_andANewAddressReplacesIt() {
        DataStore.rememberServerAddresses(mapOf("node.example" to "192.0.2.1"), learned = 1_700_000_000_000)
        val stored = DataStore.configurationStore.getString(Key.SERVER_ADDRESSES)
        DataStore.rememberServerAddresses(mapOf("node.example" to "192.0.2.1"), learned = 1_800_000_000_000)
        assertSame(stored, DataStore.configurationStore.getString(Key.SERVER_ADDRESSES))
        DataStore.rememberServerAddresses(mapOf("node.example" to "192.0.2.9"), learned = 1_800_000_000_000)
        val entry = JSONObject(DataStore.configurationStore.getString(Key.SERVER_ADDRESSES)!!).getJSONObject("node.example")
        assertEquals("192.0.2.9" to 1_800_000_000_000L, entry.getString("address") to entry.getLong("learned"))
    }

    // At the limit a new name drops the one written longest ago, so a lookup there moves its name to the end again.
    @Test
    fun atTheLimit_aNameLookedUpAgainOutlivesTheNextNewName() {
        DataStore.rememberServerAddresses((1..DataStore.SERVER_ADDRESSES_KEPT).associate { "node$it.example" to "192.0.2.1" })
        DataStore.rememberServerAddresses(mapOf("node1.example" to "192.0.2.1"))
        DataStore.rememberServerAddresses(mapOf("new.example" to "192.0.2.2"))
        val kept = DataStore.serverAddresses
        assertEquals(DataStore.SERVER_ADDRESSES_KEPT, kept.size)
        assertTrue("node1.example" in kept && "new.example" in kept)
        assertFalse("node2.example" in kept)
    }
}
