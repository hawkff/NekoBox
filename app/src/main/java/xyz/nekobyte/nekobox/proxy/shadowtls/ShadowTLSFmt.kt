package xyz.nekobyte.nekobox.proxy.shadowtls

import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundTLS

fun buildSingBoxOutboundShadowTLSBean(bean: ShadowTLSBean): SingBoxOptions.Outbound_ShadowTLSOptions = SingBoxOptions.Outbound_ShadowTLSOptions().apply {
    type = "shadowtls"
    server = bean.serverAddress
    server_port = bean.serverPort
    version = bean.version
    password = bean.password
    tls = buildSingBoxOutboundTLS(bean)
}
