package xyz.nekobyte.nekobox.fmt

import android.app.Application
import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.SubscriptionBean
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StoredProfileCompatibilityTest {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val archivedFixtureTypes = mapOf(
        "xyz.nekobyte.nekobox.fmt.trojan_go.TrojanGoBean" to 7,
        "xyz.nekobyte.nekobox.fmt.masterdnsvpn.MasterDnsVpnBean" to 25,
        "xyz.nekobyte.nekobox.fmt.olcrtc.OlcrtcBean" to 27,
    )

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/profiles/$name")).readText()
    private fun String.bytes() = trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bean(name: String) = Class.forName(name).getDeclaredConstructor().newInstance() as Serializable

    @Test
    fun frozenJavaBlobsDecodeAndReencodeWithoutChangingStoredFields() {
        for (entry in JsonParser.parseString(resource("profiles.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["name"].asString
            val bytes = fixture["hex"].asString.bytes()
            val archivedType = archivedFixtureTypes[fixture["class"].asString]
            if (archivedType != null) {
                val archived = ArchivedBean(archivedType, bytes)
                assertArrayEquals(name, bytes, KryoConverters.serialize(archived.clone()))
                val restored = parseUniversal(archived.toUniversalLink()) as ArchivedBean
                assertEquals(name, archivedType, restored.originalType)
                assertArrayEquals(name, bytes, KryoConverters.serialize(restored))
                continue
            }
            val decoded = bean(fixture["class"].asString)
            ByteBufferInput(bytes).use { input ->
                decoded.deserializeFromBuffer(input)
                assertEquals(name, bytes.size, input.position())
            }
            decoded.initializeDefaultValues()
            assertEquals(name, fixture["decoded"], gson.toJsonTree(decoded))
            assertArrayEquals(name, fixture["canonicalHex"].asString.bytes(), KryoConverters.serialize(decoded))
            if (decoded is AbstractBean) {
                assertArrayEquals(name, KryoConverters.serialize(decoded), KryoConverters.serialize(decoded.clone()))
            }
        }
    }

    @Test
    fun publicParcelableCreatorsPreserveFrozenProfiles() {
        for (entry in JsonParser.parseString(resource("profiles.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["name"].asString
            val archivedType = archivedFixtureTypes[fixture["class"].asString]
            val instance = if (archivedType != null) {
                ArchivedBean(archivedType, fixture["hex"].asString.bytes())
            } else {
                KryoConverters.deserialize(bean(fixture["class"].asString), fixture["hex"].asString.bytes())
            }
            val creator = instance.javaClass.getField("CREATOR").get(null) as Parcelable.Creator<*>
            val array = creator.newArray(2)
            assertEquals(name, 2, array.size)
            assertTrue(name, array.all { it == null })
            val parcel = Parcel.obtain()
            try {
                instance.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = creator.createFromParcel(parcel) as Serializable
                if (archivedType != null) {
                    assertEquals(name, archivedType, (restored as ArchivedBean).originalType)
                    assertArrayEquals(name, fixture["hex"].asString.bytes(), KryoConverters.serialize(restored))
                } else {
                    assertEquals(name, fixture["decoded"], gson.toJsonTree(restored))
                    assertArrayEquals(name, fixture["canonicalHex"].asString.bytes(), KryoConverters.serialize(restored))
                }
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun activeConstructorsKeepNullableFieldsAndTheirOriginalDefaults() {
        for (entry in JsonParser.parseString(resource("constructors.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["class"].asString
            if (name in archivedFixtureTypes) continue
            val instance = bean(name)
            assertEquals(name, fixture["fresh"], gson.toJsonTree(instance))
            assertEquals(
                name,
                fixture["fields"],
                gson.toJsonTree(
                    instance.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }
                        .associate { it.name to it.type.name },
                ),
            )
            instance.initializeDefaultValues()
            assertEquals(name, fixture["defaults"], gson.toJsonTree(instance))
        }
    }

    @Test
    fun subscriptionShareBytesRemainCompatible() {
        val bytes = resource("subscription-share.hex").bytes()
        val subscription = SubscriptionBean()
        ByteBufferInput(bytes).use { input ->
            subscription.deserializeFromShare(input)
            assertEquals(bytes.size, input.position())
        }
        subscription.initializeDefaultValues()
        assertEquals("https://example.com/subscription", subscription.link)
        ByteBufferOutput(8192).use { output ->
            subscription.serializeForShare(output)
            assertArrayEquals(bytes, output.toBytes())
        }
    }
}
