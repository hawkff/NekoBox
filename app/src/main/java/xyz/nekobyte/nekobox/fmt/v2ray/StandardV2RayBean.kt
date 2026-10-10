package xyz.nekobyte.nekobox.fmt.v2ray

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import java.util.Locale

abstract class StandardV2RayBean : AbstractBean() {
    @JvmField var uuid: String? = null

    @JvmField var encryption: String? = null

    @JvmField var vlessEncryption: String? = null

    @JvmField var type: String? = null

    @JvmField var host: String? = null

    @JvmField var path: String? = null

    @JvmField var security: String? = null

    @JvmField var sni: String? = null

    @JvmField var alpn: String? = null

    @JvmField var utlsFingerprint: String? = null

    @JvmField var allowInsecure: Boolean? = null

    @JvmField var realityPubKey: String? = null

    @JvmField var realityShortId: String? = null

    @JvmField var wsMaxEarlyData: Int? = null

    @JvmField var earlyDataHeaderName: String? = null

    @JvmField var certificates: String? = null

    @JvmField var xhttpMode: String? = null

    @JvmField var xhttpExtra: String? = null

    @JvmField var mKcpSeed: String? = null

    @JvmField var headerType: String? = null

    @JvmField var kcpMtu: Int? = null

    @JvmField var kcpTti: Int? = null

    @JvmField var kcpCwndMultiplier: Int? = null

    @JvmField var enableECH: Boolean? = null

    @JvmField var echConfig: String? = null

    @JvmField var enableMux: Boolean? = null

    @JvmField var muxPadding: Boolean? = null

    @JvmField var muxType: Int? = null

    @JvmField var muxConcurrency: Int? = null

    @JvmField var muxMode: Int? = null

    @JvmField var muxMaxConnections: Int? = null

    @JvmField var muxMinStreams: Int? = null

    @JvmField var muxBrutal: Boolean? = null

    @JvmField var muxBrutalUpMbps: Int? = null

    @JvmField var muxBrutalDownMbps: Int? = null

    @JvmField var packetEncoding: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        uuid = uuid?.takeIf { it.isNotBlank() } ?: ""
        encryption = encryption?.takeIf { it.isNotBlank() } ?: ""
        vlessEncryption = vlessEncryption?.takeIf { it.isNotBlank() } ?: ""
        type = type?.takeIf { it.isNotBlank() } ?: "tcp"
        if (type == "h2") type = "http"
        type = type!!.lowercase(Locale.getDefault())
        host = host?.takeIf { it.isNotBlank() } ?: ""
        path = path?.takeIf { it.isNotBlank() } ?: ""
        security = security?.takeIf { it.isNotBlank() } ?: if (this is TrojanBean) "tls" else "none"
        sni = sni?.takeIf { it.isNotBlank() } ?: ""
        alpn = alpn?.takeIf { it.isNotBlank() } ?: ""
        certificates = certificates?.takeIf { it.isNotBlank() } ?: ""
        earlyDataHeaderName = earlyDataHeaderName?.takeIf { it.isNotBlank() } ?: ""
        utlsFingerprint = utlsFingerprint?.takeIf { it.isNotBlank() } ?: ""
        wsMaxEarlyData = wsMaxEarlyData ?: 0
        allowInsecure = allowInsecure ?: false
        packetEncoding = packetEncoding ?: 0
        realityPubKey = realityPubKey ?: ""
        realityShortId = realityShortId ?: ""
        enableECH = enableECH ?: false
        echConfig = echConfig?.takeIf { it.isNotBlank() } ?: ""
        enableMux = enableMux ?: false
        muxPadding = muxPadding ?: false
        muxType = muxType ?: 0
        muxConcurrency = muxConcurrency ?: 8
        muxMode = muxMode ?: 0
        muxMaxConnections = muxMaxConnections ?: 4
        muxMinStreams = muxMinStreams ?: 4
        muxBrutal = muxBrutal ?: false
        muxBrutalUpMbps = muxBrutalUpMbps ?: 100
        muxBrutalDownMbps = muxBrutalDownMbps ?: 100
        xhttpMode = xhttpMode?.takeIf { it.isNotBlank() } ?: "auto"
        xhttpExtra = xhttpExtra?.takeIf { it.isNotBlank() } ?: ""
        mKcpSeed = mKcpSeed?.takeIf { it.isNotBlank() } ?: ""
        headerType = headerType?.takeIf { it.isNotBlank() } ?: "none"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(10)
        super.serialize(output)
        output.writeString(uuid)
        output.writeString(encryption)
        output.writeString(vlessEncryption)
        if (this is VMessBean) output.writeInt(alterId!!)
        output.writeString(type)
        when (type!!) {
            "ws" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeInt(wsMaxEarlyData!!)
                output.writeString(earlyDataHeaderName)
            }

            "http", "httpupgrade" -> {
                output.writeString(host)
                output.writeString(path)
            }

            "grpc" -> output.writeString(path)

            "xhttp" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeString(xhttpMode)
                output.writeString(xhttpExtra)
            }

            "kcp" -> {
                output.writeString(mKcpSeed)
                output.writeString(headerType)
                output.writeInt(kcpMtu ?: 0)
                output.writeInt(kcpTti ?: 0)
                output.writeInt(kcpCwndMultiplier ?: 0)
            }
        }
        output.writeString(security)
        if (security == "tls") {
            output.writeString(sni)
            output.writeString(alpn)
            output.writeString(certificates)
            output.writeBoolean(allowInsecure!!)
            output.writeString(utlsFingerprint)
            output.writeString(realityPubKey)
            output.writeString(realityShortId)
        }
        output.writeBoolean(enableECH!!)
        output.writeString(echConfig)
        output.writeInt(packetEncoding!!)
        output.writeBoolean(enableMux!!)
        output.writeBoolean(muxPadding!!)
        output.writeInt(muxType!!)
        output.writeInt(muxConcurrency!!)
        output.writeInt(muxMode!!)
        output.writeInt(muxMaxConnections!!)
        output.writeInt(muxMinStreams!!)
        output.writeBoolean(muxBrutal!!)
        output.writeInt(muxBrutalUpMbps!!)
        output.writeInt(muxBrutalDownMbps!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        uuid = input.readString()
        encryption = input.readString()
        if (version >= 5) vlessEncryption = input.readString()
        if (this is VMessBean) alterId = input.readInt()
        type = input.readString()
        when (type!!) {
            "ws" -> {
                host = input.readString()
                path = input.readString()
                wsMaxEarlyData = input.readInt()
                earlyDataHeaderName = input.readString()
            }

            "http", "httpupgrade" -> {
                host = input.readString()
                path = input.readString()
            }

            "grpc" -> {
                path = input.readString()
                if (version < 4) {
                    input.readString()
                    input.readString()
                }
            }

            "xhttp" -> if (version >= 4) {
                host = input.readString()
                path = input.readString()
                xhttpMode = input.readString()
                xhttpExtra = input.readString()
            }

            "kcp" -> if (version >= 6) {
                mKcpSeed = input.readString()
                headerType = input.readString()
                if (version >= 9) {
                    kcpMtu = input.readInt().takeUnless { it == 0 }
                    kcpTti = input.readInt().takeUnless { it == 0 }
                    if (version >= 10) kcpCwndMultiplier = input.readInt().takeUnless { it == 0 }
                }
            }
        }
        security = input.readString()
        if (security == "tls") {
            sni = input.readString()
            alpn = input.readString()
            certificates = input.readString()
            allowInsecure = input.readBoolean()
            utlsFingerprint = input.readString()
            realityPubKey = input.readString()
            realityShortId = input.readString()
        }
        if (version >= 1) {
            enableECH = input.readBoolean()
            if (version >= 3) {
                echConfig = input.readString()
            } else if (enableECH == true) {
                input.readBoolean()
                input.readBoolean()
                echConfig = input.readString()
            }
        } else if (version == 0) {
            val position = input.byteBuffer.position()
            val probeEch = input.readBoolean()
            val probePacketEncoding = input.readInt()
            input.setPosition(position)
            if (probePacketEncoding != 1 && probePacketEncoding != 2) {
                enableECH = probeEch
                if (probeEch) {
                    input.readBoolean()
                    input.readBoolean()
                    echConfig = input.readString()
                }
            }
        }
        packetEncoding = input.readInt()
        if (version >= 2) {
            enableMux = input.readBoolean()
            muxPadding = input.readBoolean()
            muxType = input.readInt()
            muxConcurrency = input.readInt()
        }
        if (version >= 7) {
            muxMode = input.readInt()
            muxMaxConnections = input.readInt()
            muxMinStreams = input.readInt()
        }
        if (version >= 8) {
            muxBrutal = input.readBoolean()
            muxBrutalUpMbps = input.readInt()
            muxBrutalDownMbps = input.readInt()
        }
    }

    val isVLESS: Boolean get() = this is VMessBean && alterId == -1
}
