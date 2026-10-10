package xyz.nekobyte.nekobox.fmt

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.ProtocolRegistry
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArchivedProfileTest {
    @Test
    fun opaquePayloadsSurviveCloneParcelAndUniversalLinks() {
        val payload = ByteArray(9000) { (it * 31).toByte() }
        for (type in listOf(7, 25, 27, 9001)) {
            assertNull(ProtocolRegistry.forType(type))
            val bean = ArchivedBean(type, payload).applyDefaultValues()
            val decoded = KryoConverters.deserialize(ArchivedBean(type, byteArrayOf()), payload)
            assertArrayEquals(payload, KryoConverters.serialize(decoded))
            assertArrayEquals(payload, KryoConverters.serialize(bean.clone()))
            val profile = ProxyEntity(id = 9, groupId = 2, userOrder = 3, tx = 11, rx = 12).putBean(bean)
            assertFalse(profile.canBuild())
            assertFalse(profile.haveSettings())
            assertFalse(profile.haveStandardLink())
            val fromLink = parseUniversal(profile.toStdLink()) as ArchivedBean
            assertEquals(type, fromLink.originalType)
            assertArrayEquals(payload, KryoConverters.serialize(fromLink))
            val parcel = Parcel.obtain()
            try {
                bean.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = ArchivedBean.CREATOR.createFromParcel(parcel)
                assertEquals(type, restored.originalType)
                assertArrayEquals(payload, KryoConverters.serialize(restored))
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun archivesCannotImpersonateActiveTypesAndDoNotRetainMutableInput() {
        val payload = byteArrayOf(1, 2, 3)
        val bean = ArchivedBean(9001, payload)
        payload[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), KryoConverters.serialize(bean))
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEntity().putBean(ArchivedBean(ProxyEntity.TYPE_SOCKS, payload))
        }
        assertThrows(IllegalStateException::class.java) { parseUniversal("sn://archive-0?AQID") }
        val profile = ProxyEntity().putBean(bean)
        profile.putBean(SOCKSBean().applyDefaultValues())
        assertNull(profile.archivedData)
        assertEquals(ProxyEntity.TYPE_SOCKS, profile.type)
    }
}
