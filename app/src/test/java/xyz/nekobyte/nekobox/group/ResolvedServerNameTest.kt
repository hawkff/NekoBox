package xyz.nekobyte.nekobox.group

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.tuic.TuicBean
import xyz.nekobyte.nekobox.proxy.anytls.AnyTLSBean
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ResolvedServerNameTest {
    @Test
    fun forcedResolutionPreservesDefaultAndExplicitTlsNames() {
        for (sni in listOf("", "explicit.example")) {
            val tuic = TuicBean().apply {
                serverAddress = "proxy.example"
                this.sni = sni
                initializeDefaultValues()
            }
            val anytls = AnyTLSBean().apply {
                serverAddress = "proxy.example"
                this.sni = sni
                initializeDefaultValues()
            }
            for (bean in listOf(tuic, anytls)) {
                RawUpdater.rewriteAddress(bean, listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))), false)
                assertEquals("192.0.2.1", bean.serverAddress)
            }
            val expected = sni.ifEmpty { "proxy.example" }
            assertEquals(expected, tuic.sni)
            assertEquals(expected, anytls.sni)
        }
    }

    @Test
    fun tuicClashIpOverrideKeepsTheCertificateHostname() = runBlocking {
        for (sni in listOf("", "explicit.example")) {
            val text = """
                proxies:
                  - name: node
                    type: tuic
                    server: proxy.example
                    ip: 192.0.2.1
                    port: 443
                    uuid: 00000000-0000-4000-8000-000000000001
                    password: secret
                    sni: "$sni"
            """.trimIndent()
            val bean = RawUpdater.parseRaw(text)!!.single() as TuicBean
            assertEquals("192.0.2.1", bean.serverAddress)
            assertEquals(sni.ifEmpty { "proxy.example" }, bean.sni)
        }
    }
}
