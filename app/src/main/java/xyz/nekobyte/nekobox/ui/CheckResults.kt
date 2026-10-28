package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.ItemCheckResultBinding
import xyz.nekobyte.nekobox.ktx.EaseOut
import xyz.nekobyte.nekobox.ktx.getColorAttr

/** How a finding of the connection check or the NAT test reads. */
enum class CheckTone { OK, WARNING, INFO }

private class ToneLook(val icon: Int, val color: Int, val name: Int)

private fun Context.look(tone: CheckTone) = when (tone) {
    CheckTone.OK -> ToneLook(R.drawable.ic_action_done, getColorAttr(androidx.appcompat.R.attr.colorPrimary), R.string.check_tone_ok)
    CheckTone.WARNING -> ToneLook(R.drawable.ic_baseline_warning_24, getColorAttr(androidx.appcompat.R.attr.colorError), R.string.check_tone_warning)
    CheckTone.INFO -> ToneLook(R.drawable.ic_baseline_info_24, getColorAttr(android.R.attr.textColorSecondary), R.string.check_tone_info)
}

/** One finding: the tone's icon, named for screen readers, then what was checked and what came out. */
fun ItemCheckResultBinding.showResult(@StringRes title: Int, status: CharSequence, tone: CheckTone, detail: CharSequence? = null) {
    val look = root.context.look(tone)
    resultIcon.setImageResource(look.icon)
    resultIcon.imageTintList = ColorStateList.valueOf(look.color)
    resultIcon.contentDescription = root.context.getString(look.name)
    resultTitle.setText(title)
    resultStatus.text = status
    resultDetail.text = detail
    resultDetail.isVisible = !detail.isNullOrEmpty()
}

/** A check's overall answer, after the tone's icon; a neutral answer keeps the text color. */
fun TextView.showVerdict(text: CharSequence, tone: CheckTone) {
    val look = context.look(tone)
    this.text = text
    setTextColor(if (tone == CheckTone.INFO) context.getColorAttr(android.R.attr.textColorPrimary) else look.color)
    val icon = ContextCompat.getDrawable(context, look.icon)?.mutate()?.apply { setTint(look.color) }
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    isVisible = true
    reveal(0)
}

/**
 * Brings a result in with a short rise and fade, [order] places after the first of its batch, so a
 * list arrives as a list. With animations off it simply appears.
 */
fun View.reveal(order: Int) {
    animate().cancel()
    alpha = 0f
    translationY = 12 * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(minOf(order * 35L, 245L)).setDuration(220).setInterpolator(EaseOut).start()
}
