package xyz.nekobyte.nekobox.fmt.naive

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class NaiveBean : AbstractBean() {
    @JvmField
    var proto: String? = null

    @JvmField
    var username: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var extraHeaders: String? = null

    @JvmField
    var sni: String? = null

    @JvmField
    var certificates: String? = null

    @JvmField
    var insecureConcurrency: Int? = null

    @JvmField
    var sUoT: Boolean? = null

    override fun initializeDefaultValues() {
        serverPort = serverPort ?: 443
        super.initializeDefaultValues()
        proto = proto ?: "https"
        username = username ?: ""
        password = password ?: ""
        extraHeaders = extraHeaders ?: ""
        certificates = certificates ?: ""
        sni = sni ?: ""
        insecureConcurrency = insecureConcurrency ?: 0
        sUoT = sUoT ?: false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(3)
        super.serialize(output)
        output.writeString(proto)
        output.writeString(username)
        output.writeString(password)
        output.writeString(extraHeaders)
        output.writeString(certificates)
        output.writeString(sni)
        output.writeInt(insecureConcurrency!!)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        proto = input.readString()
        username = input.readString()
        password = input.readString()
        extraHeaders = input.readString()
        if (version >= 2) {
            certificates = input.readString()
            sni = input.readString()
        }
        if (version >= 1) {
            insecureConcurrency = input.readInt()
        }
        if (version >= 3) {
            sUoT = input.readBoolean()
        }
    }

    override fun clone(): NaiveBean = KryoConverters.deserialize(NaiveBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<NaiveBean> = object : Serializable.CREATOR<NaiveBean>() {
            override fun newInstance() = NaiveBean()
            override fun newArray(size: Int): Array<NaiveBean?> = arrayOfNulls(size)
        }
    }
}
