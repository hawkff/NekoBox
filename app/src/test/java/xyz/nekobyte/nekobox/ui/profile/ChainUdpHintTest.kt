package xyz.nekobyte.nekobox.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.ssh.SSHBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean

class ChainUdpHintTest {
    private fun profile(bean: AbstractBean) = ProxyEntity().putBean(bean.apply { initializeDefaultValues() })

    @Test
    fun tcpEntry_doesNotRuleOutEncapsulatedUdp() {
        val http = profile(HttpBean())
        val vmess = profile(VMessBean())
        val socks = profile(SOCKSBean())
        val uot = profile(SOCKSBean().apply { sUoT = true })
        val socks4 = profile(SOCKSBean().apply { protocol = SOCKSBean.PROTOCOL_SOCKS4 })
        val ssh = profile(SSHBean())

        assertEquals(R.string.chain_hint_udp_generic, chainUdpHint(listOf(http, vmess)))
        assertEquals(R.string.chain_hint_udp_uot, chainUdpHint(listOf(http, uot)))
        assertEquals(R.string.chain_hint_udp_socks, chainUdpHint(listOf(http, socks)))
        for (exit in listOf(http, ssh, socks4)) {
            assertEquals(R.string.chain_hint_udp_tcp_only, chainUdpHint(listOf(vmess, exit)))
        }
    }
}
