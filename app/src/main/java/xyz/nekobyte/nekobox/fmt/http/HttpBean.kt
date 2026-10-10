package xyz.nekobyte.nekobox.fmt.http

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean

class HttpBean : StandardV2RayBean() {
    @JvmField
    var username: String? = null

    @JvmField
    var password: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        username = username ?: ""
        password = password ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(username)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        password = input.readString()
    }

    override fun clone(): HttpBean = KryoConverters.deserialize(HttpBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HttpBean> = object : Serializable.CREATOR<HttpBean>() {
            override fun newInstance() = HttpBean()
            override fun newArray(size: Int): Array<HttpBean?> = arrayOfNulls(size)
        }
    }
}
