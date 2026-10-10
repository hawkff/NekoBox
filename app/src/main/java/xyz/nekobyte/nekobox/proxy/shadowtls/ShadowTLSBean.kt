package xyz.nekobyte.nekobox.proxy.shadowtls

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean

class ShadowTLSBean : StandardV2RayBean() {
    @JvmField
    var version: Int? = null

    @JvmField
    var password: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        security = "tls"
        version = version ?: 3
        password = password ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeInt(version!!)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version_ = input.readInt()
        super.deserialize(input)
        version = input.readInt()
        password = input.readString()
    }

    override fun clone(): ShadowTLSBean = KryoConverters.deserialize(ShadowTLSBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ShadowTLSBean> = object : Serializable.CREATOR<ShadowTLSBean>() {
            override fun newInstance() = ShadowTLSBean()
            override fun newArray(size: Int): Array<ShadowTLSBean?> = arrayOfNulls(size)
        }
    }
}
