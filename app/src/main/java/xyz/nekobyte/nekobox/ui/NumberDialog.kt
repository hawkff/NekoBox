package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.LayoutNumberDialogBinding

/** Asks for a whole number in [range]; OK stays off until the field holds one. */
fun numberDialog(context: Context, title: CharSequence?, current: Int, range: IntRange, save: (Int) -> Unit) {
    val binding = LayoutNumberDialogBinding.inflate(LayoutInflater.from(context))
    binding.numberLayout.helperText = context.getString(R.string.number_range, range.first, range.last)
    binding.number.setText(current.toString())
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(binding.root)
        .setPositiveButton(android.R.string.ok) { _, _ -> binding.number.text.toString().toIntOrNull()?.let(save) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    fun check() {
        ok.isEnabled = binding.number.text.toString().toIntOrNull() in range
    }
    binding.number.doAfterTextChanged { check() }
    check()
}
