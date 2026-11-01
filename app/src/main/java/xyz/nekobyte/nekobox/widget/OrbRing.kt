package xyz.nekobyte.nekobox.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.EaseOut
import xyz.nekobyte.nekobox.ktx.animationsOn
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The connect button's orb: a black hole ([BlackHole]) inside a ring of small marks (+, ✳ and dots). Off, the
 * accretion disk is orange and still and the ring grey. Connecting, the disk spins up and turns blue. Connected, a
 * quasar's burst (jets along the poles, an X-ray ring, a flash), then the disk turns slowly in blue while the ring,
 * lit blue with a rim, twinkles. Off again, the disk slows back to orange and the ring goes grey. A connection that
 * never comes up goes back the same way, without the burst. Everything moves only while the button shows and
 * animations are on; without animations each state shows at once.
 */
internal class OrbRing(private val view: View) {
    private class Mark(val radius: Float, angle: Float, val kind: Int, val weight: Float, spin: Float, val pace: Float, val phase: Float) {
        val x = cos(angle) * radius
        val y = sin(angle) * radius
        val spinX = cos(spin)
        val spinY = sin(spin)
    }

    private val marks: List<Mark>
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hole = BlackHole()

    var offColor = 0

    /** Whether the orb sits on a dark theme; light themes take the deeper blue for the ring and the rim. */
    var dark
        get() = hole.dark
        set(value) {
            hole.dark = value
        }

    // What shows, each eased towards its state's value: the disk's color from orange (0) to blue (1), how lit the
    // ring is, its rim, how much the lit ring twinkles, and how fast the disk turns, in turns a second.
    internal val levels = FloatArray(LEVELS)

    /** The connection's burst, 0 to 1 while it plays and -1 otherwise. */
    internal var burst = -1f
        private set
    private var spin = 0f
    private var seconds = 0f
    private var sinceDraw = 0L
    private var state = BaseService.State.Idle
    private var change: ValueAnimator? = null
    private var flare: ValueAnimator? = null
    private var clock: TimeAnimator? = null
    private var running = true

    /** Whether the clock turns the disk and twinkles the ring now. */
    internal val ticking get() = clock?.let { it.isStarted && !it.isPaused } == true

    init {
        // The same ring every time: placed by the golden angle, denser along the middle of the band.
        val random = Random(7)
        marks = List(MARKS) { index ->
            val t = (0.5f + random.nextGaussian().toFloat() * 0.24f).coerceIn(0f, 1f)
            val kind = random.nextFloat().let {
                if (it < 0.45f) {
                    PLUS
                } else if (it < 0.8f) {
                    STAR
                } else {
                    DOT
                }
            }
            val weight = 0.55f + random.nextFloat() * 0.45f
            Mark(INNER + (OUTER - INNER) * t, index * GOLDEN + (random.nextFloat() - 0.5f) * 0.24f, kind, weight, random.nextFloat() * TAU, 2.2f + 2.5f * weight, random.nextFloat() * TAU)
        }
    }

    /** Shows [state]: animated from what shows now when [animate], at once otherwise. */
    fun show(state: BaseService.State, animate: Boolean) {
        val previous = this.state
        this.state = state
        val moving = animate && view.context.animationsOn()
        val target = when (state) {
            BaseService.State.Connecting -> CONNECTING
            BaseService.State.Connected -> CONNECTED
            else -> OFF
        }
        when {
            !moving -> {
                flare?.cancel()
                set(target)
            }

            state == BaseService.State.Connected && previous == BaseService.State.Connecting -> {
                startBurst()
                tween(target, SETTLE)
            }

            // Stopped while the disk still slows from Stopping: it finishes.
            previous == BaseService.State.Stopping && change?.isRunning == true && target === OFF -> Unit

            else -> tween(target, if (state == BaseService.State.Connecting) SPIN_UP else SLOW_DOWN)
        }
        updateClock()
    }

    /** Stops the clock while the button is gone: hidden, out of the window or in the background. */
    fun pause() {
        running = false
        updateClock()
    }

    fun resume() {
        running = true
        updateClock()
    }

    private fun set(target: FloatArray) {
        change?.cancel()
        change = null
        target.copyInto(levels)
        view.invalidate()
    }

    private fun tween(target: FloatArray, duration: Long, interpolator: TimeInterpolator = EaseOut) {
        change?.cancel()
        val from = levels.copyOf()
        change = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in levels.indices) levels[i] = from[i] + (target[i] - from[i]) * f
                view.invalidate()
                updateClock()
            }
            start()
        }
    }

    private fun startBurst() {
        flare?.cancel()
        flare = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BURST
            interpolator = LinearInterpolator()
            addUpdateListener {
                burst = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    burst = -1f
                    view.invalidate()
                }
            })
            start()
        }
    }

    // The clock runs while the disk turns or the ring twinkles, and the button shows with animations on.
    private fun updateClock() {
        val run = running && (levels[SPEED] > 0.001f || levels[TWINKLE] > 0.001f) && view.context.animationsOn()
        val clock = clock
        if (run) {
            when {
                clock == null -> this.clock = TimeAnimator().apply {
                    setTimeListener { _, _, delta -> tick(delta) }
                    start()
                }

                clock.isPaused -> clock.resume()
            }
        } else if (clock != null && clock.isStarted && !clock.isPaused) {
            clock.pause()
        }
    }

    private fun tick(delta: Long) {
        spin = (spin + levels[SPEED] * 360f * delta / 1000f) % 360f
        seconds += delta / 1000f
        sinceDraw += delta
        // Settled, the slow turn and the twinkle need no more than 30 frames a second.
        val settled = change?.isRunning != true && flare?.isRunning != true
        if (!settled || sinceDraw >= 33) {
            sinceDraw = 0
            view.invalidate()
        }
        if (levels[SPEED] <= 0.001f && levels[TWINKLE] <= 0.001f) updateClock()
    }

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return
        val lit = levels[LIT]
        val twinkle = levels[TWINKLE]
        val ringColor = if (dark) BlackHole.BLUE_MID else BlackHole.BLUE_EDGE
        // The burst lights the ring up for a moment.
        val flash = if (burst in 0f..0.45f) 1 - burst / 0.45f else 0f
        for (mark in marks) {
            val sparkle = 1 - twinkle * (0.45f - 0.45f * sin(seconds * mark.pace + mark.phase))
            val alpha = ((0.42f * mark.weight * (1 - lit) + (0.5f + 0.5f * mark.weight) * lit) * sparkle + 0.5f * flash).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            drawMark(canvas, cx, cy, mark, radius, ColorUtils.setAlphaComponent(ColorUtils.blendARGB(offColor, ringColor, lit), (alpha * 255).toInt()))
        }
        hole.draw(canvas, cx, cy, radius, levels[HUE], spin, burst)
        val rim = levels[RIM]
        if (rim > 0f) {
            stroke.strokeWidth = radius * RIM_WIDTH
            stroke.color = ColorUtils.setAlphaComponent(ringColor, (0.8f * rim * 255).toInt())
            canvas.drawCircle(cx, cy, radius - stroke.strokeWidth / 2, stroke)
        }
    }

    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, mark: Mark, radius: Float, color: Int) {
        val x = cx + mark.x * radius
        val y = cy + mark.y * radius
        if (mark.kind == DOT) {
            fill.color = color
            canvas.drawCircle(x, y, radius * DOT_RADIUS, fill)
            return
        }
        stroke.color = color
        stroke.strokeWidth = radius * STROKE
        val arm = radius * ARM
        // A plus is two strokes, a star three, turned by the mark's own angle.
        val strokes = if (mark.kind == PLUS) 2 else 3
        for (i in 0 until strokes) {
            val turn = PI.toFloat() * i / strokes
            val dx = (mark.spinX * cos(turn) - mark.spinY * sin(turn)) * arm
            val dy = (mark.spinY * cos(turn) + mark.spinX * sin(turn)) * arm
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
        }
    }

    internal companion object {
        const val HUE = 0
        const val LIT = 1
        const val RIM = 2
        const val TWINKLE = 3
        const val SPEED = 4
        private const val LEVELS = 5

        // Hue, ring, rim, twinkle, turns a second.
        private val OFF = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        private val CONNECTING = floatArrayOf(1f, 0.35f, 0f, 0f, 1f)
        private val CONNECTED = floatArrayOf(1f, 1f, 1f, 1f, 0.15f)

        private const val SPIN_UP = 1200L
        private const val SETTLE = 1400L
        private const val SLOW_DOWN = 700L
        private const val BURST = 850L

        private const val TAU = (2 * PI).toFloat()
        private const val GOLDEN = 2.399963f
        private const val MARKS = 72

        // The ring's band and its marks, as fractions of the orb's radius, clear of the disk inside.
        private const val INNER = 25.5f / 36
        private const val OUTER = 33.5f / 36
        private const val ARM = 1.35f / 36
        private const val STROKE = 0.75f / 36
        private const val DOT_RADIUS = 0.6f / 36
        private const val RIM_WIDTH = 0.67f / 36
        private const val PLUS = 0
        private const val STAR = 1
        private const val DOT = 2
    }
}
