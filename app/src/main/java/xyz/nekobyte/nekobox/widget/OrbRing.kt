package xyz.nekobyte.nekobox.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.EaseIn
import xyz.nekobyte.nekobox.ktx.EaseOut
import xyz.nekobyte.nekobox.ktx.animationsOn
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The connect button's orb: small marks (+, ✳ and dots) and six diamonds in a ring around the plane. Off, it is grey
 * and still. Connecting, a lit arc sweeps around it and the diamonds orbit. Connected, the light spreads back from
 * where the sweep stood until the whole ring is lit, with a rim and a faint fill of the same color. Stopping, the lit
 * marks drift out and fade while the grey ones come back. Only the sweep loops, while the button shows and animations
 * are on; without animations each state shows at once, connecting as a still arc.
 */
internal class OrbRing(private val view: View) {
    // Sizes are fractions of the orb's radius: the ring runs from INNER to OUTER, clear of the plane in the middle.
    private class Mark(radius: Float, angle: Float, val kind: Int, val weight: Float, spin: Float) {
        val angle = angle.mod(TAU)
        val x = cos(angle) * radius
        val y = sin(angle) * radius
        val spinX = cos(spin)
        val spinY = sin(spin)
    }

    private val marks: List<Mark>
    private val orbits = Path()
    private val orbitBounds = RectF()
    private var orbitRadius = 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    var offColor = 0
    var litColor = 0

    // What shows, each animated toward its target: the sweep's arc, how far the light has spread behind its head (a
    // whole turn at 1) and how far the lit marks have drifted out on their way off.
    internal var head = 0f
        private set
    internal var lit = 0f
        private set
    internal var scatter = 0f
        private set
    private var sweep = -PI.toFloat() / 2
    private var orbit = 0f
    private var state = BaseService.State.Idle
    private var change: ValueAnimator? = null
    private var loop: TimeAnimator? = null
    private var running = true

    /** Whether the sweep goes round now. */
    internal val sweeping get() = loop?.let { it.isStarted && !it.isPaused } == true

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
            Mark(INNER + (OUTER - INNER) * t, index * GOLDEN + (random.nextFloat() - 0.5f) * 0.24f, kind, 0.55f + random.nextFloat() * 0.45f, random.nextFloat() * TAU)
        }
    }

    /** Shows [state]: animated from what shows now when [animate], at once otherwise. */
    fun show(state: BaseService.State, animate: Boolean) {
        val previous = this.state
        this.state = state
        val moving = animate && view.context.animationsOn()
        when (state) {
            BaseService.State.Connecting -> {
                startLoop()
                if (moving) tween(SWEEP_IN, EaseOut, head = 1f, lit = 0f, scatter = 0f) else set(1f, 0f, 0f)
            }

            BaseService.State.Connected -> if (moving && head > 0f) {
                // The light spreads back from the sweep's head; once the whole ring is lit, the sweep has done its work.
                tween(FLOOD, EaseOut, lit = 1f, scatter = 0f) {
                    stopLoop()
                    set(0f, 1f, 0f)
                }
            } else {
                stopLoop()
                set(0f, 1f, 0f)
            }

            else -> {
                stopLoop()
                when {
                    !moving -> set(0f, 0f, 0f)

                    // Stopped while the marks still drift off from Stopping: they finish.
                    previous == BaseService.State.Stopping && change?.isRunning == true -> Unit

                    lit > 0f -> tween(SCATTER, EaseIn, head = 0f, scatter = 1f) { set(0f, 0f, 0f) }

                    // A connection that never came up: the arc fades.
                    head > 0f -> tween(SWEEP_OUT, EaseIn, head = 0f)

                    else -> set(0f, 0f, 0f)
                }
            }
        }
    }

    /** Stops the sweep while the button is gone: hidden, or out of the window. */
    fun pause() {
        running = false
        loop?.pause()
    }

    fun resume() {
        running = true
        if (state == BaseService.State.Connecting) startLoop()
    }

    private fun set(head: Float, lit: Float, scatter: Float) {
        change?.cancel()
        change = null
        this.head = head
        this.lit = lit
        this.scatter = scatter
        view.invalidate()
    }

    private fun tween(duration: Long, interpolator: TimeInterpolator, head: Float = this.head, lit: Float = this.lit, scatter: Float = this.scatter, end: () -> Unit = {}) {
        change?.cancel()
        val from = floatArrayOf(this.head, this.lit, this.scatter)
        change = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                val f = it.animatedValue as Float
                this@OrbRing.head = from[0] + (head - from[0]) * f
                this@OrbRing.lit = from[1] + (lit - from[1]) * f
                this@OrbRing.scatter = from[2] + (scatter - from[2]) * f
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) end()
                }
            })
            start()
        }
    }

    private fun startLoop() {
        if (!running || !view.context.animationsOn()) return
        val loop = loop ?: TimeAnimator().apply {
            setTimeListener { _, _, delta ->
                sweep = (sweep + delta * TAU / SWEEP_TURN).mod(TAU)
                orbit = (orbit + delta * TAU / ORBIT_TURN).mod(TAU)
                view.invalidate()
            }
        }.also { loop = it }
        if (loop.isPaused) {
            loop.resume()
        } else if (!loop.isStarted) {
            loop.start()
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return
        if (orbitRadius != radius) buildOrbits(radius)
        val shown = lit * (1 - scatter)
        // The faint fill and rim of a lit orb.
        if (shown > 0f) {
            fill.color = ColorUtils.setAlphaComponent(litColor, (FILL_ALPHA * shown * 255).toInt())
            canvas.drawCircle(cx, cy, radius, fill)
        }
        stroke.strokeWidth = radius * HAIRLINE
        stroke.color = blend(offColor, litColor, shown, 0.14f + 0.1f * shown)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(orbits, stroke)
        for (mark in marks) {
            val behind = (sweep - mark.angle).mod(TAU)
            val glow = head * max(0f, 1 - behind / TAIL).pow(1.4f)
            // Marks the light has reached are lit whole; its front is soft.
            val flood = if (lit >= 1f) 1f else ((lit * TAU - behind) / FRONT + 1).coerceIn(0f, 1f) * min(1f, lit * 8)
            if (scatter > 0f) {
                // Lit, drifting out and fading; grey, coming back in place.
                drawMark(canvas, mark, radius, 1 + DRIFT * scatter, max(glow, flood) * (1 - scatter), litOnly = true)
                drawMark(canvas, mark, radius, 1f, 0f, alphaScale = scatter)
            } else {
                drawMark(canvas, mark, radius, 1f, max(glow, flood), scale = 1 + 0.35f * glow)
            }
        }
        for (k in 0 until DIAMONDS) {
            val angle = k * TAU / DIAMONDS + 0.35f + orbit * 0.35f
            val amount = max(head, shown)
            fill.color = blend(offColor, litColor, amount, 0.43f + 0.57f * amount)
            val size = radius * DIAMOND
            canvas.save()
            canvas.translate(cos(angle) * radius * MIDDLE, sin(angle) * radius * MIDDLE)
            canvas.rotate(45f)
            canvas.drawRect(-size, -size, size, size, fill)
            canvas.restore()
        }
        canvas.restore()
        if (shown > 0f) {
            stroke.strokeWidth = radius * RIM
            stroke.color = ColorUtils.setAlphaComponent(litColor, (0.78f * shown * 255).toInt())
            canvas.drawCircle(cx, cy, radius - stroke.strokeWidth / 2, stroke)
        }
    }

    // One mark, [amount] of the way from grey to lit.
    private fun drawMark(canvas: Canvas, mark: Mark, radius: Float, drift: Float, amount: Float, scale: Float = 1f, alphaScale: Float = 1f, litOnly: Boolean = false) {
        val alpha = if (litOnly) amount * (0.5f + 0.5f * mark.weight) else alphaScale * (0.42f * mark.weight + (0.08f + 0.5f * mark.weight) * amount)
        if (alpha <= 0.01f) return
        val color = blend(offColor, litColor, if (litOnly) 1f else min(1f, amount * 1.6f), alpha)
        val x = mark.x * radius * drift
        val y = mark.y * radius * drift
        if (mark.kind == DOT) {
            fill.color = color
            canvas.drawCircle(x, y, radius * DOT_RADIUS * scale, fill)
            return
        }
        stroke.color = color
        stroke.strokeWidth = radius * STROKE * scale
        val arm = radius * ARM * scale
        // A plus is two strokes, a star three, turned by the mark's own angle.
        val strokes = if (mark.kind == PLUS) 2 else 3
        for (i in 0 until strokes) {
            val turn = PI.toFloat() * i / strokes
            val dx = (mark.spinX * cos(turn) - mark.spinY * sin(turn)) * arm
            val dy = (mark.spinY * cos(turn) + mark.spinX * sin(turn)) * arm
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
        }
    }

    // Two faint orbits crossing the ring, as tilted ellipses.
    private fun buildOrbits(radius: Float) {
        orbitRadius = radius
        orbits.reset()
        for (tilt in floatArrayOf(24f, -34f)) {
            val ellipse = Path()
            orbitBounds.set(-radius * 0.917f, -radius * 0.764f, radius * 0.917f, radius * 0.764f)
            ellipse.addOval(orbitBounds, Path.Direction.CW)
            orbits.addPath(ellipse, android.graphics.Matrix().apply { setRotate(tilt) })
        }
    }

    private fun blend(from: Int, to: Int, amount: Float, alpha: Float): Int = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(from, to, amount.coerceIn(0f, 1f)), (alpha.coerceIn(0f, 1f) * 255).toInt())

    private companion object {
        const val TAU = (2 * PI).toFloat()
        const val GOLDEN = 2.399963f
        const val MARKS = 88
        const val DIAMONDS = 6
        const val INNER = 22f / 36
        const val OUTER = 33.5f / 36
        const val MIDDLE = 28f / 36
        const val ARM = 1.35f / 36
        const val STROKE = 0.75f / 36
        const val DOT_RADIUS = 0.6f / 36
        const val DIAMOND = 1.2f / 36
        const val HAIRLINE = 0.34f / 36
        const val RIM = 0.67f / 36
        const val FILL_ALPHA = 0.14f
        const val DRIFT = 0.18f
        const val FRONT = 0.35f
        const val TAIL = 160f / 180 * PI.toFloat()
        const val PLUS = 0
        const val STAR = 1
        const val DOT = 2
        const val SWEEP_TURN = 1100f
        const val ORBIT_TURN = 3200f
        const val SWEEP_IN = 180L
        const val SWEEP_OUT = 220L
        const val FLOOD = 520L
        const val SCATTER = 380L
    }
}
