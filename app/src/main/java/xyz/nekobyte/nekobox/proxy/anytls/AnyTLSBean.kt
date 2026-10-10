package xyz.nekobyte.nekobox.proxy.anytls

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class AnyTLSBean : AbstractBean() {
    @JvmField
    var password: String? = null

    @JvmField
    var sni: String? = null

    @JvmField
    var alpn: String? = null

    @JvmField
    var certificates: String? = null

    @JvmField
    var utlsFingerprint: String? = null

    @JvmField
    var allowInsecure: Boolean? = null

    @JvmField
    var echConfig: String? = null

    @JvmField
    var realityPubKey: String? = null

    @JvmField
    var realityShortId: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        password = password ?: ""
        sni = sni ?: ""
        alpn = alpn ?: ""
        certificates = certificates ?: ""
        utlsFingerprint = utlsFingerprint ?: ""
        allowInsecure = allowInsecure ?: false
        echConfig = echConfig ?: ""
        realityPubKey = realityPubKey ?: ""
        realityShortId = realityShortId ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeString(certificates)
        output.writeString(utlsFingerprint)
        output.writeBoolean(allowInsecure!!)
        output.writeString(echConfig)
        output.writeString(realityPubKey)
        output.writeString(realityShortId)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        password = input.readString()
        sni = input.readString()
        alpn = input.readString()
        certificates = input.readString()
        utlsFingerprint = input.readString()
        allowInsecure = input.readBoolean()
        echConfig = input.readString()
        if (version >= 1) {
            realityPubKey = input.readString()
            realityShortId = input.readString()
        } else {
            realityPubKey = ""
            realityShortId = ""
        }
    }

    override fun clone(): AnyTLSBean = KryoConverters.deserialize(AnyTLSBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AnyTLSBean> = object : Serializable.CREATOR<AnyTLSBean>() {
            override fun newInstance() = AnyTLSBean()
            override fun newArray(size: Int): Array<AnyTLSBean?> = arrayOfNulls(size)
        }
    }
}
