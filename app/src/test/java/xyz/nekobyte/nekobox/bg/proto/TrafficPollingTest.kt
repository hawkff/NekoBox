package xyz.nekobyte.nekobox.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficPollingTest {
    @Test
    fun disablingNotificationUpdatesKeepsAccountingAndSelectionPolling() {
        assertEquals(1_000L, trafficPollingInterval(0))
        assertEquals(1_000L, trafficPollingInterval(-1))
        assertEquals(500L, trafficPollingInterval(500))
        assertEquals(2_000L, trafficPollingInterval(2_000))
    }

    // The lists are sent the profiles this session counted traffic for; the rest they show from what is stored.
    @Test
    fun onlyAProfileTheSessionCountedFor_isSentToTheLists() {
        val idle = TrafficUpdater.TrafficLooperData(tag = "idle", rx = 300, tx = 400, rxBase = 300, txBase = 400)
        assertFalse(idle.counted)
        assertTrue(TrafficUpdater.TrafficLooperData(tag = "used", rx = 301, tx = 400, rxBase = 300, txBase = 400).counted)
    }

    // Clearing on a screen starts the totals from zero, while the lifetime totals still get all of the session.
    @Test
    fun clearedTotals_startFromZero_andKeepTheSessionForTheLifetimeTotals() {
        val item = TrafficUpdater.TrafficLooperData(tag = "used", rx = 350, tx = 480, rxBase = 300, txBase = 400)
        item.clear()
        assertEquals(0L to 0L, item.rx to item.tx)
        assertEquals(50L to 80L, item.rx - item.rxBase to item.tx - item.txBase)
        // The zeros are still sent: a list may show what it read before the clear.
        assertTrue(item.counted)
        item.rx += 7
        assertEquals(7L to 57L, item.rx to item.rx - item.rxBase)
        // A second clear with nothing counted in between changes nothing more.
        item.clear()
        item.clear()
        assertEquals(57L, item.rx - item.rxBase)
    }
}
