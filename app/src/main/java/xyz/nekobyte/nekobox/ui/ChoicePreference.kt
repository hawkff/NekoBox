package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.core.content.res.use
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
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

    // The rows sit in a plain scrolling column: a dialog's list view sizes itself before its rows wrap, which
    // cut off rows with a second line.
    override fun onClick() {
        val names = entries ?: return
        val values = entryValues ?: return
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = NestedScrollView(context).apply { addView(column) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val checked = checkedIndex()
        val inflater = LayoutInflater.from(dialog.context)
        names.forEachIndexed { index, name ->
            val row = inflater.inflate(R.layout.item_choice, column, false) as CheckedTextView
            row.text = row(name, entrySummaries?.getOrNull(index))
            row.isChecked = index == checked
            row.setOnClickListener {
                dialog.dismiss()
                choose(values[index].toString())
            }
            ViewCompat.setAccessibilityDelegate(row, RadioRole)
            column.addView(row)
        }
        dialog.show()
        // A long list opens with its picked row in view.
        column.getChildAt(checked)?.let { row -> scroll.post { scroll.scrollTo(0, row.top - (scroll.height - row.height) / 2) } }
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

    /** Screen readers announce a row as a radio button, picked or not. */
    private object RadioRole : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = RadioButton::class.java.name
        }
    }
}
