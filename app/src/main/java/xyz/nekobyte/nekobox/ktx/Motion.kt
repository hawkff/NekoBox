package xyz.nekobyte.nekobox.ktx

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Entrances and state changes: exponential ease-out, quick to move and soft to settle. res/interpolator/ease_out_expo.xml is the same curve. */
val EaseOut: TimeInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

/** Exits: exponential ease-in, the mirror of [EaseOut]. res/interpolator/ease_in_expo.xml is the same curve. */
val EaseIn: TimeInterpolator = PathInterpolator(0.7f, 0f, 0.84f, 0f)

/**
 * Animates the changes about to be made inside this view, as a fold opening or closing: what goes fades
 * out at once, the rest moves to its place, and what comes fades in as its room opens. The platform's
 * default plays the three one after another, 300 ms each. Each part gets the curve itself: before
 * Android 10 a set does not hand its curve down.
 *
 * [resized] views, and what they hold, change size where they stand, as a button whose label changes
 * or a chip that gains its check: they take their new size at once, since a growing outline would cut
 * off their text while it grows.
 */
fun ViewGroup.beginFold(vararg resized: View) {
    val fold = TransitionSet()
        .addTransition(Fade(Fade.OUT).setDuration(100).setInterpolator(EaseOut))
        .addTransition(ChangeBounds().setDuration(250).setInterpolator(EaseOut))
        .addTransition(Fade(Fade.IN).setStartDelay(50).setDuration(200).setInterpolator(EaseOut))
    // The set walks the views and skips what it excludes along with everything inside.
    for (view in resized) fold.excludeTarget(view, true)
    TransitionManager.beginDelayedTransition(this, fold)
}

/** Whether the system plays animations: off when the user removed them or set their duration to zero. */
fun Context.animationsOn(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
    ValueAnimator.areAnimatorsEnabled()
} else {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
