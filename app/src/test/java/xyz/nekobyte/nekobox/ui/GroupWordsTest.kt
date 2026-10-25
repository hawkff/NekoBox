package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.database.SubscriptionBean

class GroupWordsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun subscription(userinfo: String) = SubscriptionBean().apply { subscriptionUserinfo = userinfo }

    @Test
    fun theQuotaIsWhatTheProviderReports() {
        val quota = GroupWords.quota(subscription("upload=100; download=400; total=1000; expire=${(now + 10 * day) / 1000}"))!!
        assertEquals(500L, quota.used)
        assertEquals(1000L, quota.total)
        assertEquals(10, quota.daysLeft(now))
        assertFalse(quota.low(now))
    }

    @Test
    fun littleLeftIsATenthOfTheTrafficOrThreeDays() {
        assertTrue(GroupWords.quota(subscription("upload=0; download=900; total=1000"))!!.low(now))
        assertTrue(GroupWords.quota(subscription("download=1; expire=${(now + 3 * day) / 1000}"))!!.low(now))
        assertFalse(GroupWords.quota(subscription("download=899; total=1000"))!!.low(now))
    }

    @Test
    fun withoutAnythingReportedThereIsNoQuota() {
        assertNull(GroupWords.quota(null))
        assertNull(GroupWords.quota(subscription("")))
        // 3x-ui sends 0 for no total and no end.
        assertNull(GroupWords.quota(subscription("upload=0; download=0; total=0; expire=0")))
        val unlimited = GroupWords.quota(subscription("upload=10; download=20; total=0; expire=0"))!!
        assertNull(unlimited.total)
        assertNull(unlimited.ends)
    }
}
