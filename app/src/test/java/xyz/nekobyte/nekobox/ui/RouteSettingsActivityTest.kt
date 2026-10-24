package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule editor refuses what would keep the core from starting. */
class RouteSettingsActivityTest {
    @Test
    fun portsAreNumbersOrRanges() {
        for (entry in listOf("443", "1000:2000", ":1024", "8000:", "65535")) assertTrue(entry, RouteSettingsActivity.isPorts(entry))
        for (entry in listOf("0", "65536", "http", "2000:1000", ":", "1:2:3", "-1")) assertFalse(entry, RouteSettingsActivity.isPorts(entry))
    }

    @Test
    fun downloadedListsNeedTheirKindAndALink() {
        assertTrue(RouteSettingsActivity.isDownloadedList("rssite:https://example.org/sites.srs"))
        assertTrue(RouteSettingsActivity.isDownloadedList("rsip:http://example.org/addresses.json"))
        for (entry in listOf("https://example.org/list.srs", "rssite:example.org/list.srs", "rsip:", "geosite:cn")) {
            assertFalse(entry, RouteSettingsActivity.isDownloadedList(entry))
        }
    }
}
