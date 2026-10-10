package xyz.nekobyte.nekobox.fmt.socks

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class SOCKSBean : AbstractBean() {
    @JvmField
    var protocol: Int? = null

    @JvmField
    var sUoT: Boolean? = null

    @JvmField
    var username: String? = null

    @JvmField
    var password: String? = null

    fun protocolVersion(): Int {
        when (protocol!!) {
            0, 1 -> {
                return 4
            }

            else -> {
                return 5
            }
        }
    }

    fun protocolName(): String {
        when (protocol!!) {
            0 -> {
                return "SOCKS4"
            }

            1 -> {
                return "SOCKS4A"
            }

            else -> {
                return "SOCKS5"
            }
        }
    }

    fun protocolVersionName(): String {
        when (protocol!!) {
            0 -> {
                return "4"
            }

            1 -> {
                return "4a"
            }

            else -> {
                return "5"
            }
        }
    }

    override fun network(): String {
        if (protocol!! < PROTOCOL_SOCKS5) {
            return "tcp"
        }
        return super.network()
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        protocol = protocol ?: PROTOCOL_SOCKS5
        username = username ?: ""
        password = password ?: ""
        sUoT = sUoT ?: false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeInt(protocol!!)
        output.writeString(username)
        output.writeString(password)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        if (version >= 1) {
            protocol = input.readInt()
        }
        username = input.readString()
        password = input.readString()
        if (version >= 2) {
            sUoT = input.readBoolean()
        }
    }

    override fun clone(): SOCKSBean = KryoConverters.deserialize(SOCKSBean(), KryoConverters.serialize(this))

    companion object {
        const val PROTOCOL_SOCKS4 = 0
        const val PROTOCOL_SOCKS4A = 1
        const val PROTOCOL_SOCKS5 = 2

        @JvmField
        val CREATOR: Parcelable.Creator<SOCKSBean> = object : Serializable.CREATOR<SOCKSBean>() {
            override fun newInstance() = SOCKSBean()
            override fun newArray(size: Int): Array<SOCKSBean?> = arrayOfNulls(size)
        }
    }
}
