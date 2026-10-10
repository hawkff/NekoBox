package xyz.nekobyte.nekobox.fmt.snell

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class SnellBean : AbstractBean() {
    @JvmField
    var psk: String? = null

    @JvmField
    var version: Int? = null

    @JvmField
    var obfsMode: String? = null

    @JvmField
    var obfsHost: String? = null

    @JvmField
    var reuse: Boolean? = null

    @JvmField
    var network: String? = null

    override fun initializeDefaultValues() {
        serverPort = serverPort ?: 443
        version = version ?: 4
        psk = psk ?: ""
        obfsMode = obfsMode ?: ""
        obfsHost = obfsHost ?: ""
        reuse = reuse ?: false
        network = network ?: ""
        super.initializeDefaultValues()
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(psk)
        output.writeInt(version!!)
        output.writeString(obfsMode)
        output.writeString(obfsHost)
        output.writeBoolean(reuse!!)
        output.writeString(network)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        psk = input.readString()
        this.version = input.readInt()
        obfsMode = input.readString()
        obfsHost = input.readString()
        reuse = input.readBoolean()
        if (version >= 2) {
            network = input.readString()
        }
    }

    override fun clone(): SnellBean = KryoConverters.deserialize(SnellBean(), KryoConverters.serialize(this))

    override fun displayName(): String = name!!

    override fun displayAddress(): String = serverAddress!!

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SnellBean> = object : Serializable.CREATOR<SnellBean>() {
            override fun newInstance() = SnellBean()
            override fun newArray(size: Int): Array<SnellBean?> = arrayOfNulls(size)
        }
    }
}
