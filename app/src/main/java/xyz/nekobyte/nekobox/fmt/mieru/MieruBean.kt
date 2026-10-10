/*
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package xyz.nekobyte.nekobox.fmt.mieru

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.Serializable

class MieruBean : AbstractBean() {
    @JvmField
    var protocol: String? = null

    @JvmField
    var username: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var mtu: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        protocol = protocol ?: "TCP"
        username = username ?: ""
        password = password ?: ""
        mtu = mtu ?: 1400
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(protocol)
        output.writeString(username)
        output.writeString(password)
        if (protocol!!.equals("UDP")) {
            output.writeInt(mtu!!)
        }
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        protocol = input.readString()
        username = input.readString()
        password = input.readString()
        if (protocol!!.equals("UDP")) {
            mtu = input.readInt()
        }
    }

    override fun clone(): MieruBean = KryoConverters.deserialize(MieruBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MieruBean> = object : Serializable.CREATOR<MieruBean>() {
            override fun newInstance() = MieruBean()
            override fun newArray(size: Int): Array<MieruBean?> = arrayOfNulls(size)
        }
    }
}
