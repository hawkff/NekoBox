package xyz.nekobyte.nekobox.proxy.config

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonObject
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable
import xyz.nekobyte.nekobox.fmt.internal.InternalBean
import xyz.nekobyte.nekobox.utils.JavaUtil

class ConfigBean : InternalBean() {
    @JvmField
    var type: Int? = null

    @JvmField
    var config: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        type = type ?: 0
        config = config ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeInt(type!!)
        output.writeString(config)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        type = input.readInt()
        config = input.readString()
    }

    override fun displayName(): String {
        if (!name.isNullOrBlank()) {
            return name!!
        } else {
            return ("Custom " + kotlin.math.abs(hashCode()))
        }
    }

    fun displayType(): String {
        if (((type != null) && (type!! == 1)) && !config.isNullOrBlank()) {
            try {
                val json = JavaUtil.gson.fromJson(config, JsonObject::class.java)
                if ((json != null) && json!!.has("type")) {
                    return (json!!.get("type").getAsString() + " (sing-box)")
                }
            } catch (_: Exception) {
            }
        }
        return (if ((type != null) && (type!! == 0)) "sing-box config" else "sing-box outbound")
    }

    override fun clone(): ConfigBean = KryoConverters.deserialize(ConfigBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ConfigBean> = object : Serializable.CREATOR<ConfigBean>() {
            override fun newInstance() = ConfigBean()
            override fun newArray(size: Int): Array<ConfigBean?> = arrayOfNulls(size)
        }
    }
}
