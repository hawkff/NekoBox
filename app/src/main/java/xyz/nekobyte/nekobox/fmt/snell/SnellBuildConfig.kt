package xyz.nekobyte.nekobox.fmt.snell

import xyz.nekobyte.nekobox.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions = SingBoxOptions.Outbound_SnellOptions().apply {
    type = "snell"
    server = bean.serverAddress
    server_port = bean.serverPort
    psk = bean.psk
    version = bean.version

    if (bean.network != null && bean.network!!.isNotBlank()) {
        network = bean.network
    }

    if (bean.obfsMode != null && bean.obfsMode!!.isNotBlank()) {
        obfs_mode = if (bean.version != null && bean.version!! >= 4 && bean.obfsMode == "tls") "" else bean.obfsMode
        if (!obfs_mode.isNullOrBlank() && bean.obfsHost != null && bean.obfsHost!!.isNotBlank()) {
            obfs_host = bean.obfsHost
        }
    }

    if (bean.reuse != null && bean.reuse!!) {
        this.reuse = true
    }
}
