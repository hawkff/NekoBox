package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.juicity.JuicityBean
import xyz.nekobyte.nekobox.fmt.juicity.buildSingBoxOutboundJuicityBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class JuicityFmtTest {
    @Before fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun buildSingBoxOutboundJuicityBean_pinWithoutHashKeepsVerification() {
        for (pin in listOf(",", ":")) {
            val bean = JuicityBean().apply {
                serverAddress = "example.com"
                pinnedCertchainSha256 = pin
                initializeDefaultValues()
            }
            val outbound = buildSingBoxOutboundJuicityBean(bean)
            assertNull(pin, outbound.pin_cert_sha256)
            assertEquals(pin, false, outbound.tls!!.insecure)
        }
    }
}
