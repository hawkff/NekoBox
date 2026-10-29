package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.widget.ArrayAdapter
import androidx.core.content.res.use
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr

/**
 * A choice from a list, picked in a dialog: one radio row per entry, with a line under the entries
 * that need explaining ([entrySummaries]). Picking a row saves it and closes the dialog.
 */
open class ChoicePreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListPreference(context, attrs) {

    /** One line per entry, shown under its name; null when the names say enough. */
    var entrySummaries: Array<CharSequence>? = context.obtainStyledAttributes(attrs, R.styleable.ChoicePreference).use {
        it.getTextArray(R.styleable.ChoicePreference_entrySummaries)
    }

    init {
        isSingleLineTitle = false
    }

    override fun onClick() {
        val names = entries ?: return
        val values = entryValues ?: return
        val rows = names.mapIndexed { index, name -> row(name, entrySummaries?.getOrNull(index)) }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(ArrayAdapter(context, R.layout.item_choice, rows), checkedIndex()) { dialog, which ->
                dialog.dismiss()
                choose(values[which].toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The row shown as picked. */
    protected open fun checkedIndex() = findIndexOfValue(value)

    /** Saves a picked value; a subclass may ask for more first. */
    protected open fun choose(newValue: String) {
        if (newValue != value && callChangeListener(newValue)) value = newValue
    }

    private fun row(name: CharSequence, summary: CharSequence?): CharSequence {
        if (summary.isNullOrEmpty()) return name
        return SpannableStringBuilder(name).append('\n').apply {
            val start = length
            append(summary)
            setSpan(RelativeSizeSpan(0.875f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(context.getColorAttr(android.R.attr.textColorSecondary)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
