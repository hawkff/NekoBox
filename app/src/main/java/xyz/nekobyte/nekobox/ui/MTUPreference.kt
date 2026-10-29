package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.util.AttributeSet

/** MTU from the list, or any value in [CUSTOM_RANGE] through its Custom row. */
class MTUPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ChoicePreference(context, attrs) {

    init {
        setSummaryProvider { value }
    }

    // A value that is not in the list was typed in: its row is Custom, the last one.
    override fun checkedIndex() = findIndexOfValue(value).takeIf { it >= 0 } ?: entryValues.lastIndex

    override fun choose(newValue: String) {
        if (newValue != CUSTOM) return super.choose(newValue)
        numberDialog(context, title, value?.toIntOrNull() ?: 1500, CUSTOM_RANGE) { super.choose(it.toString()) }
    }

    private companion object {
        const val CUSTOM = "custom"
        val CUSTOM_RANGE = 1000..10000
    }
}
