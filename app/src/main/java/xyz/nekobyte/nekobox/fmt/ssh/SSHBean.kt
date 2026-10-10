package xyz.nekobyte.nekobox.fmt.ssh

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class SSHBean : AbstractBean() {
    @JvmField
    var username: String? = null

    @JvmField
    var authType: Int? = null

    @JvmField
    var password: String? = null

    @JvmField
    var privateKey: String? = null

    @JvmField
    var privateKeyPassphrase: String? = null

    @JvmField
    var publicKey: String? = null

    override fun initializeDefaultValues() {
        serverPort = serverPort ?: 22
        super.initializeDefaultValues()
        username = username ?: "root"
        authType = authType ?: AUTH_TYPE_PASSWORD
        password = password ?: ""
        privateKey = privateKey ?: ""
        privateKeyPassphrase = privateKeyPassphrase ?: ""
        publicKey = publicKey ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(username)
        output.writeInt(authType!!)
        when (authType!!) {
            AUTH_TYPE_NONE -> {
            }

            AUTH_TYPE_PASSWORD -> {
                output.writeString(password)
            }

            AUTH_TYPE_PRIVATE_KEY -> {
                output.writeString(privateKey)
                output.writeString(privateKeyPassphrase)
            }
        }
        output.writeString(publicKey)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        authType = input.readInt()
        when (authType!!) {
            AUTH_TYPE_NONE -> {
            }

            AUTH_TYPE_PASSWORD -> {
                password = input.readString()
            }

            AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = input.readString()
                privateKeyPassphrase = input.readString()
            }
        }
        publicKey = input.readString()
    }

    override fun clone(): SSHBean = KryoConverters.deserialize(SSHBean(), KryoConverters.serialize(this))

    companion object {
        const val AUTH_TYPE_NONE = 0
        const val AUTH_TYPE_PASSWORD = 1
        const val AUTH_TYPE_PRIVATE_KEY = 2

        @JvmField
        val CREATOR: Parcelable.Creator<SSHBean> = object : Serializable.CREATOR<SSHBean>() {
            override fun newInstance() = SSHBean()
            override fun newArray(size: Int): Array<SSHBean?> = arrayOfNulls(size)
        }
    }
}
