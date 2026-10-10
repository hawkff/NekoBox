package xyz.nekobyte.nekobox.fmt

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.app

class ArchivedBean(val originalType: Int, data: ByteArray) : AbstractBean() {
    private var payload = data.copyOf()

    override fun displayName() = app.getString(R.string.profile_archived)
    override fun displayAddress() = ""
    override fun canICMPing() = false
    override fun canTCPing() = false
    override fun canMapping() = false

    override fun serializeToBuffer(output: ByteBufferOutput) = output.writeBytes(payload)
    override fun deserializeFromBuffer(input: ByteBufferInput) {
        payload = input.readBytes()
    }

    override fun clone() = ArchivedBean(originalType, payload).apply { initializeDefaultValues() }
    override fun equals(other: Any?) = other is ArchivedBean && originalType == other.originalType && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * originalType + payload.contentHashCode()
    override fun toString() = "Archived profile type $originalType"

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(originalType)
        dest.writeByteArray(payload)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchivedBean> {
            override fun createFromParcel(source: Parcel) = ArchivedBean(source.readInt(), requireNotNull(source.createByteArray())).apply { initializeDefaultValues() }
            override fun newArray(size: Int): Array<ArchivedBean?> = arrayOfNulls(size)
        }
    }
}
