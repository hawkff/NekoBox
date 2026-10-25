package xyz.nekobyte.nekobox.ktx

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Whether the system plays animations: off when the user removed them or set their duration to zero. */
fun Context.animationsOn(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
    ValueAnimator.areAnimatorsEnabled()
} else {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
