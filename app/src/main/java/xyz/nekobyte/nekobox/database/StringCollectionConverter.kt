package xyz.nekobyte.nekobox.database

import androidx.room.TypeConverter

class StringCollectionConverter {
    companion object {
        const val SPLIT_FLAG = ","

        @TypeConverter
        @JvmStatic
        fun fromSet(set: Set<String>): String = if (set.isEmpty()) {
            ""
        } else {
            set.joinToString(SPLIT_FLAG)
        }

        @TypeConverter
        @JvmStatic
        fun toSet(str: String): Set<String> = if (str.isBlank()) {
            emptySet()
        } else {
            str.split(",").toSet()
        }
    }
}
