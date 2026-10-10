package xyz.nekobyte.nekobox.fmt.tuic

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class TuicBean : AbstractBean() {
    @JvmField
    var token: String? = null

    @JvmField
    var caText: String? = null

    @JvmField
    var udpRelayMode: String? = null

    @JvmField
    var congestionController: String? = null

    @JvmField
    var alpn: String? = null

    @JvmField
    var disableSNI: Boolean? = null

    @JvmField
    var reduceRTT: Boolean? = null

    @JvmField
    var mtu: Int? = null

    @JvmField
    var sni: String? = null

    @JvmField
    var fastConnect: Boolean? = null

    @JvmField
    var allowInsecure: Boolean? = null

    @JvmField
    var customJSON: String? = null

    @JvmField
    var protocolVersion: Int? = null

    @JvmField
    var uuid: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        token = token ?: ""
        caText = caText ?: ""
        udpRelayMode = udpRelayMode ?: "native"
        congestionController = congestionController ?: "cubic"
        alpn = alpn ?: ""
        disableSNI = disableSNI ?: false
        reduceRTT = reduceRTT ?: false
        mtu = mtu ?: 1400
        sni = sni ?: ""
        fastConnect = fastConnect ?: false
        allowInsecure = allowInsecure ?: false
        customJSON = customJSON ?: ""
        protocolVersion = protocolVersion ?: 5
        uuid = uuid ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(token)
        output.writeString(caText)
        output.writeString(udpRelayMode)
        output.writeString(congestionController)
        output.writeString(alpn)
        output.writeBoolean(disableSNI!!)
        output.writeBoolean(reduceRTT!!)
        output.writeInt(mtu!!)
        output.writeString(sni)
        output.writeBoolean(fastConnect!!)
        output.writeBoolean(allowInsecure!!)
        output.writeString(customJSON)
        output.writeInt(protocolVersion!!)
        output.writeString(uuid)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        token = input.readString()
        caText = input.readString()
        udpRelayMode = input.readString()
        congestionController = input.readString()
        alpn = input.readString()
        disableSNI = input.readBoolean()
        reduceRTT = input.readBoolean()
        mtu = input.readInt()
        sni = input.readString()
        if (version >= 1) {
            fastConnect = input.readBoolean()
            allowInsecure = input.readBoolean()
        }
        if (version >= 2) {
            customJSON = input.readString()
            protocolVersion = input.readInt()
            uuid = input.readString()
        } else {
            protocolVersion = 4
        }
    }

    override fun canTCPing(): Boolean = false

    override fun clone(): TuicBean = KryoConverters.deserialize(TuicBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TuicBean> = object : Serializable.CREATOR<TuicBean>() {
            override fun newInstance() = TuicBean()
            override fun newArray(size: Int): Array<TuicBean?> = arrayOfNulls(size)
        }
    }
}
