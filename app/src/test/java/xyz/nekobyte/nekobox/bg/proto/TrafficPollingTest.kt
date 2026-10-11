package xyz.nekobyte.nekobox.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficPollingTest {
    @Test
    fun disablingNotificationUpdatesKeepsAccountingAndSelectionPolling() {
        assertEquals(1_000L, trafficPollingInterval(0))
        assertEquals(1_000L, trafficPollingInterval(-1))
        assertEquals(500L, trafficPollingInterval(500))
        assertEquals(2_000L, trafficPollingInterval(2_000))
    }
}
