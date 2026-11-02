package xyz.nekobyte.nekobox.widget

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean

// The card's code, ink on yellow, reads back as the link a scanner would see; only a provider's http(s) link shares.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionQrTest {
    private fun read(link: String): String {
        val code = NeonQr.encode(link)
        val scale = 4
        val pixels = NeonQr.pixels(code, scale)
        assertTrue("only ink and yellow", pixels.all { it == NeonQr.INK || it == NeonQr.YELLOW })
        val side = code.width * scale
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
    }

    @Test
    fun theCodeReadsBackAsTheLink() {
        val link = "https://sub.example.com/api/v1/client/subscribe?token=0123456789abcdef0123456789abcdef"
        assertEquals(link, read(link))
        // A link outside Latin-1 goes as UTF-8.
        val unicode = "https://пример.example/подписка/0123456789"
        assertEquals(unicode, read(unicode))
    }

    private fun group(type: Int, link: String?) = ProxyGroup(type = type, subscription = SubscriptionBean().apply { this.link = link })

    @Test
    fun onlyASubscriptionsHttpLinkShares() {
        assertEquals("https://sub.example.com/s/abc", group(GroupType.SUBSCRIPTION, " https://sub.example.com/s/abc\n").subscriptionLink())
        assertEquals("http://sub.example.com/s/abc", group(GroupType.SUBSCRIPTION, "http://sub.example.com/s/abc").subscriptionLink())
        assertNull(group(GroupType.SUBSCRIPTION, "").subscriptionLink())
        assertNull(group(GroupType.SUBSCRIPTION, null).subscriptionLink())
        assertNull(group(GroupType.SUBSCRIPTION, "sn://subscription?abc").subscriptionLink())
        // A list of proxies kept from a server list is no subscription to share.
        assertNull(group(GroupType.PROXIES, "https://api.example.com/relays/").subscriptionLink())
        assertNull(ProxyGroup(type = GroupType.BASIC).subscriptionLink())
    }
}
