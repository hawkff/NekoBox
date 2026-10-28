package xyz.nekobyte.nekobox.ktx

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.animation.PathInterpolator

/** Entrances and state changes: exponential ease-out, quick to move and soft to settle. res/interpolator/ease_out_expo.xml is the same curve. */
val EaseOut: TimeInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

/** Exits: exponential ease-in, the mirror of [EaseOut]. res/interpolator/ease_in_expo.xml is the same curve. */
val EaseIn: TimeInterpolator = PathInterpolator(0.7f, 0f, 0.84f, 0f)

/** Whether the system plays animations: off when the user removed them or set their duration to zero. */
fun Context.animationsOn(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
    ValueAnimator.areAnimatorsEnabled()
} else {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
