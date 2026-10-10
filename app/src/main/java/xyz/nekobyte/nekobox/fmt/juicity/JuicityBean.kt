package xyz.nekobyte.nekobox.fmt.juicity

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class JuicityBean : AbstractBean() {
    @JvmField
    var uuid: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var sni: String? = null

    @JvmField
    var pinnedCertchainSha256: String? = null

    @JvmField
    var allowInsecure: Boolean? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        uuid = uuid ?: ""
        password = password ?: ""
        sni = sni ?: ""
        pinnedCertchainSha256 = pinnedCertchainSha256 ?: ""
        allowInsecure = allowInsecure ?: false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(uuid)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(pinnedCertchainSha256)
        output.writeBoolean(allowInsecure!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        uuid = input.readString()
        password = input.readString()
        sni = input.readString()
        pinnedCertchainSha256 = input.readString()
        allowInsecure = input.readBoolean()
    }

    override fun canTCPing(): Boolean = false

    override fun clone(): JuicityBean = KryoConverters.deserialize(JuicityBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<JuicityBean> = object : Serializable.CREATOR<JuicityBean>() {
            override fun newInstance() = JuicityBean()
            override fun newArray(size: Int): Array<JuicityBean?> = arrayOfNulls(size)
        }
    }
}
