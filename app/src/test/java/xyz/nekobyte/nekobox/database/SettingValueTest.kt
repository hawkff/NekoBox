package xyz.nekobyte.nekobox.database

import org.junit.Assert.*
import org.junit.Test
import xyz.nekobyte.nekobox.database.preference.KeyValuePair
import java.nio.ByteBuffer

class SettingValueTest {
    @Test
    fun validatesSupportedValuesAndUnicodeSets() {
        val unicode = setOf("café", "東京", "")
        val values = listOf(
            KeyValuePair("unset"),
            KeyValuePair("boolean").put(true),
            KeyValuePair("float").put(1.25f),
            KeyValuePair("long").put(5L),
            KeyValuePair("string").put("東京"),
            KeyValuePair("set").put(unicode),
        )
        values.forEach { it.validateValue() }
        assertEquals(unicode, values.last().stringSet)
    }

    @Test
    @Suppress("DEPRECATION")
    fun rejectsTruncatedNumbersAndUnknownTypes() {
        for ((type, bytes) in listOf(
            KeyValuePair.TYPE_BOOLEAN to 1,
            KeyValuePair.TYPE_FLOAT to 4,
            KeyValuePair.TYPE_INT to 4,
            KeyValuePair.TYPE_LONG to 8,
        )) {
            for (size in listOf(0, bytes - 1, bytes + 1).distinct()) {
                assertThrows(IllegalArgumentException::class.java) { value(type, ByteArray(size)).validateValue() }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { value(999, byteArrayOf()).validateValue() }
    }

    @Test
    fun rejectsInvalidSetLengthsWithoutAllocatingThem() {
        for (bytes in listOf(
            byteArrayOf(0),
            ByteBuffer.allocate(4).putInt(-1).array(),
            ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array(),
            ByteBuffer.allocate(5).putInt(2).put(1).array(),
        )) {
            assertThrows(IllegalArgumentException::class.java) { value(KeyValuePair.TYPE_STRING_SET, bytes).validateValue() }
        }
    }

    private fun value(type: Int, bytes: ByteArray) = KeyValuePair("value").apply {
        valueType = type
        value = bytes
    }
}
