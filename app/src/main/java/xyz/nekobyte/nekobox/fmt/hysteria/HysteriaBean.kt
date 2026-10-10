package xyz.nekobyte.nekobox.fmt.hysteria

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable
import xyz.nekobyte.nekobox.ktx.wrapIPV6Host

class HysteriaBean : AbstractBean() {
    @JvmField
    var protocolVersion: Int? = null

    @JvmField
    var serverPorts: String? = null

    @JvmField
    var authPayload: String? = null

    @JvmField
    var obfuscation: String? = null

    @JvmField
    var sni: String? = null

    @JvmField
    var caText: String? = null

    @JvmField
    var uploadMbps: Int? = null

    @JvmField
    var downloadMbps: Int? = null

    @JvmField
    var allowInsecure: Boolean? = null

    @JvmField
    var streamReceiveWindow: Int? = null

    @JvmField
    var connectionReceiveWindow: Int? = null

    @JvmField
    var disableMtuDiscovery: Boolean? = null

    @JvmField
    var hopInterval: Int? = null

    @JvmField
    var alpn: String? = null

    @JvmField
    var authPayloadType: Int? = null

    @JvmField
    var protocol: Int? = null

    @JvmField
    var hysteria2ObfsType: Int? = null

    @JvmField
    var geckoMinPacketSize: Int? = null

    @JvmField
    var geckoMaxPacketSize: Int? = null

    @JvmField
    var enableECH: Boolean? = null

    @JvmField
    var echConfig: String? = null

    override fun canMapping(): Boolean = (protocol!! != PROTOCOL_FAKETCP)

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        protocolVersion = protocolVersion ?: 2
        authPayloadType = authPayloadType ?: TYPE_NONE
        authPayload = authPayload ?: ""
        protocol = protocol ?: PROTOCOL_UDP
        obfuscation = obfuscation ?: ""
        hysteria2ObfsType = hysteria2ObfsType ?: (if (obfuscation!!.isEmpty()) OBFS_NONE else OBFS_SALAMANDER)
        geckoMinPacketSize = geckoMinPacketSize ?: 512
        geckoMaxPacketSize = geckoMaxPacketSize ?: 1200
        enableECH = enableECH ?: false
        echConfig = echConfig ?: ""
        sni = sni ?: ""
        alpn = alpn ?: ""
        caText = caText ?: ""
        allowInsecure = allowInsecure ?: false
        if (protocolVersion!! == 1) {
            uploadMbps = uploadMbps ?: 10
            downloadMbps = downloadMbps ?: 50
        } else {
            uploadMbps = uploadMbps ?: 0
            downloadMbps = downloadMbps ?: 0
        }
        streamReceiveWindow = streamReceiveWindow ?: 0
        connectionReceiveWindow = connectionReceiveWindow ?: 0
        disableMtuDiscovery = disableMtuDiscovery ?: false
        hopInterval = hopInterval ?: 10
        serverPorts = serverPorts ?: "443"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(9)
        super.serialize(output)
        output.writeInt(protocolVersion!!)
        output.writeInt(authPayloadType!!)
        output.writeString(authPayload)
        output.writeInt(protocol!!)
        output.writeString(obfuscation)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeInt(uploadMbps!!)
        output.writeInt(downloadMbps!!)
        output.writeBoolean(allowInsecure!!)
        output.writeString(caText)
        output.writeInt(streamReceiveWindow!!)
        output.writeInt(connectionReceiveWindow!!)
        output.writeBoolean(disableMtuDiscovery!!)
        output.writeInt(hopInterval!!)
        output.writeString(serverPorts)
        output.writeInt(hysteria2ObfsType!!)
        output.writeInt(geckoMinPacketSize!!)
        output.writeInt(geckoMaxPacketSize!!)
        output.writeBoolean((enableECH == true))
        output.writeString(echConfig)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        if (version >= 7) {
            protocolVersion = input.readInt()
        } else {
            protocolVersion = 1
        }
        authPayloadType = input.readInt()
        authPayload = input.readString()
        if (version >= 3) {
            protocol = input.readInt()
        }
        obfuscation = input.readString()
        sni = input.readString()
        if (version >= 2) {
            alpn = input.readString()
        }
        uploadMbps = input.readInt()
        downloadMbps = input.readInt()
        allowInsecure = input.readBoolean()
        if (version >= 1) {
            caText = input.readString()
            streamReceiveWindow = input.readInt()
            connectionReceiveWindow = input.readInt()
            if (version != 4) {
                disableMtuDiscovery = input.readBoolean()
            }
        }
        if (version >= 5) {
            hopInterval = input.readInt()
        }
        if (version >= 6) {
            serverPorts = input.readString()
        } else {
            if (isMultiPort(serverAddress!!)) {
                serverPorts = serverAddress!!.substringAfterLast(":", serverAddress!!)
                serverAddress = serverAddress!!.substringBeforeLast(":", serverAddress!!)
            } else {
                serverPorts = serverPort!!.toString()
            }
        }
        if (version >= 8) {
            hysteria2ObfsType = input.readInt()
            geckoMinPacketSize = input.readInt()
            geckoMaxPacketSize = input.readInt()
        }
        if (version >= 9) {
            enableECH = input.readBoolean()
            echConfig = input.readString()
        } else {
            enableECH = false
            echConfig = ""
        }
    }

    override fun displayAddress(): String = ((serverAddress!!.wrapIPV6Host() + ":") + serverPorts)

    override fun canTCPing(): Boolean = false

    override fun clone(): HysteriaBean = KryoConverters.deserialize(HysteriaBean(), KryoConverters.serialize(this))

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_STRING = 1
        const val TYPE_BASE64 = 2
        const val PROTOCOL_UDP = 0
        const val PROTOCOL_FAKETCP = 1
        const val PROTOCOL_WECHAT_VIDEO = 2
        const val OBFS_NONE = 0
        const val OBFS_SALAMANDER = 1
        const val OBFS_GECKO = 2

        @JvmField
        val CREATOR: Parcelable.Creator<HysteriaBean> = object : Serializable.CREATOR<HysteriaBean>() {
            override fun newInstance() = HysteriaBean()
            override fun newArray(size: Int): Array<HysteriaBean?> = arrayOfNulls(size)
        }
    }
}
