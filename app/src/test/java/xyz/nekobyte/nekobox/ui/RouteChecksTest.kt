package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule editor and the app API refuse what would keep the core from starting. */
class RouteChecksTest {
    @Test
    fun portsAreNumbersOrRanges() {
        for (entry in listOf("443", "1000:2000", ":1024", "8000:", "65535")) assertTrue(entry, RouteChecks.isPorts(entry))
        for (entry in listOf("0", "65536", "http", "2000:1000", ":", "1:2:3", "-1")) assertFalse(entry, RouteChecks.isPorts(entry))
    }

    @Test
    fun downloadedListsNeedTheirKindAndALink() {
        assertTrue(RouteChecks.isDownloadedList("rssite:https://example.org/sites.srs"))
        assertTrue(RouteChecks.isDownloadedList("rsip:http://example.org/addresses.json"))
        // The core strips one prefix and downloads the rest, so a second prefix or a link without a host fails there.
        for (entry in listOf("https://example.org/list.srs", "rssite:example.org/list.srs", "rsip:", "geosite:cn", "rssite:https://", "rssite:rsip:https://example.org/list.srs")) {
            assertFalse(entry, RouteChecks.isDownloadedList(entry))
        }
    }
}
