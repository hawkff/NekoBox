package xyz.nekobyte.nekobox.widget

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.widget.glass.Glass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The cat of the network checks: it sniffs a bowl of ramen while nothing runs, runs while a check
 * runs, and sits back when the answer is in, its eyes closed when all is well. Flat shapes in the
 * theme's colors; still when animations are off, and paused while hidden.
 */
class CheckCat @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Pose { WAITING, RUNNING, CONTENT, ALERT }

    var pose = Pose.WAITING
        set(value) {
            if (field == value) return
            field = value
            animate().cancel()
            if (!motion || !isShown) {
                drawn = value
                alpha = 1f
                invalidate()
                return
            }
            // A quick cross-fade, so the cat does not jump between poses.
            animate().alpha(0f).setDuration(90).withEndAction {
                drawn = value
                invalidate()
                animate().alpha(1f).setDuration(160).start()
            }.start()
        }

    // The pose on screen, which follows [pose] after the cross-fade.
    private var drawn = Pose.WAITING

    @ColorInt
    private val cat = context.getColorAttr(R.attr.primaryOrTextPrimary)

    @ColorInt
    private val canvasColor = Glass.canvasColor(context)

    @ColorInt
    private val onSurface = context.getColorAttr(com.google.android.material.R.attr.colorOnSurface)

    // Eyes and nose stay dark, their glint light, whichever way the theme runs.
    @ColorInt
    private val ink = if (ColorUtils.calculateLuminance(canvasColor) < ColorUtils.calculateLuminance(onSurface)) canvasColor else onSurface

    @ColorInt
    private val glint = if (ink == canvasColor) onSurface else canvasColor

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    private var motion = motionOn()
    private var time = 0f
    private val clock = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            time = total / 1000f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClock()
    }

    override fun onDetachedFromWindow() {
        clock.end()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateClock()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Animations may have been switched on or off while the page was away.
        if (visibility == VISIBLE) {
            motion = motionOn()
            invalidate()
        }
        updateClock()
    }

    private fun motionOn() = if (Build.VERSION.SDK_INT >= 26) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    private fun updateClock() {
        val run = motion && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (run && !clock.isStarted) {
            clock.start()
        } else if (!run && clock.isStarted) {
            clock.end()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val scale = min(width / WIDTH, height / HEIGHT)
        if (scale <= 0f) return
        // Without motion each pose holds its most telling moment: a sniff, a stride.
        val t = if (motion) time else STILL
        canvas.withTranslation((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2) {
            scale(scale, scale)
            if (drawn == Pose.RUNNING) running(this, t) else sitting(this, t)
        }
    }

    private fun sitting(canvas: Canvas, t: Float) {
        stroke(canvas, GROUND, onSurface, 1.5f, 0.14f)
        val sway = when (drawn) {
            Pose.ALERT -> 0f
            Pose.CONTENT -> 8 * sin(2 * PI.toFloat() * t / 4.8f)
            else -> 6 * sin(2 * PI.toFloat() * t / 3.6f)
        }
        canvas.withRotation(sway, 74f, 104f) { stroke(this, TAIL_SIT, cat, 7f) }
        fill(canvas, BODY_SIT, cat)
        stroke(canvas, LEG_LINE, ink, 1.4f, 0.5f)
        // Waiting, the cat sniffs the bowl twice every few seconds; done, it looks up.
        val angle = when (drawn) {
            Pose.ALERT -> -8f
            Pose.CONTENT -> -4f
            else -> 14 + 4 * (pulse(t % 3.2f, 0f) + pulse(t % 3.2f, 0.36f))
        }
        canvas.withRotation(angle, 98f, 58f) {
            fill(this, EAR_SIT_LEFT, cat)
            stroke(this, EAR_SIT_LEFT, cat, 3f)
            fill(this, EAR_SIT_RIGHT, cat)
            stroke(this, EAR_SIT_RIGHT, cat, 3f)
            ellipse(this, 106f, 44f, 19f, 16f, cat)
            if (drawn == Pose.CONTENT) {
                stroke(this, CLOSED_EYES, ink, 1.6f)
            } else {
                val r = if (drawn == Pose.ALERT) 2.9f else 2.4f
                eye(this, 105f, 43f, r)
                eye(this, 116f, 43f, r)
            }
            fill(this, NOSE_SIT, ink)
            stroke(this, NOSE_SIT, ink, 0.8f)
            stroke(this, MOUTH, ink, 1.1f)
            stroke(this, WHISKERS_SIT, cat, 1.3f)
        }
        for (i in 0..2) {
            val phase = (t / 2.4f + i / 3f) % 1f
            canvas.withTranslation(STEAM_X[i], 72 - 14 * phase) {
                stroke(this, STEAM, onSurface, 2.4f, 0.5f * sin(PI.toFloat() * phase))
            }
        }
        fill(canvas, NOODLES, onSurface, 0.5f)
        stroke(canvas, STRANDS, canvasColor, 1.2f, 0.5f)
        stroke(canvas, CHOPSTICKS, cat, 2.6f)
        fill(canvas, BOWL, onSurface)
        stroke(canvas, RIM, canvasColor, 1.5f)
        fillPaint.color = onSurface
        canvas.drawRoundRect(160f, 108f, 180f, 112f, 1.5f, 1.5f, fillPaint)
    }

    private fun running(canvas: Canvas, t: Float) {
        val w = 2 * PI.toFloat() * t / STRIDE
        // The ground streams past under the cat.
        linePaint.color = onSurface
        linePaint.alpha = (0.22f * 255).toInt()
        linePaint.strokeWidth = 1.5f
        var x = 28f - (t * 90) % 18
        while (x < 214f) {
            if (x + 10 > 28f) canvas.drawLine(maxOf(x, 28f), 112.5f, min(x + 10, 214f), 112.5f, linePaint)
            x += 18
        }
        canvas.withTranslation(0f, -2.5f * abs(sin(w))) {
            withRotation(2 * sin(w + PI.toFloat() / 2), 118f, 72f) {
                // The far legs, a little behind in their stride, then the tail, the body and the near legs.
                leg(this, 134f, 42 * sin(w + 0.6f), 0.6f)
                leg(this, 96f, -42 * sin(w + 0.6f), 0.6f)
                withRotation(6 * sin(w), 90f, 66f) { stroke(this, TAIL_RUN, cat, 7f) }
                fill(this, BODY_RUN, cat)
                leg(this, 134f, 42 * sin(w), 1f)
                leg(this, 96f, -42 * sin(w), 1f)
                withRotation(3 * sin(w + 1), 140f, 66f) {
                    fill(this, EAR_RUN_LEFT, cat)
                    stroke(this, EAR_RUN_LEFT, cat, 3f)
                    fill(this, EAR_RUN_RIGHT, cat)
                    stroke(this, EAR_RUN_RIGHT, cat, 3f)
                    ellipse(this, 156f, 56f, 14.5f, 12.5f, cat)
                    eye(this, 157f, 54f, 2.4f)
                    eye(this, 166f, 54f, 2.4f)
                    fill(this, NOSE_RUN, ink)
                    stroke(this, WHISKERS_RUN, cat, 1.3f)
                }
            }
        }
    }

    private fun leg(canvas: Canvas, x: Float, degrees: Float, alpha: Float) {
        val a = degrees * PI.toFloat() / 180
        linePaint.color = cat
        linePaint.alpha = (alpha * 255).toInt()
        linePaint.strokeWidth = 6.5f
        canvas.drawLine(x, 76f, x + LEG * sin(a), 76 + LEG * cos(a), linePaint)
    }

    private fun eye(canvas: Canvas, x: Float, y: Float, r: Float) {
        fillPaint.color = ink
        canvas.drawCircle(x, y, r, fillPaint)
        fillPaint.color = glint
        canvas.drawCircle(x - 0.8f, y - 0.8f, 0.75f, fillPaint)
    }

    private fun ellipse(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, @ColorInt color: Int) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fillPaint.color = color
        canvas.drawOval(oval, fillPaint)
    }

    private fun fill(canvas: Canvas, path: Path, @ColorInt color: Int, alpha: Float = 1f) {
        fillPaint.color = color
        fillPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, fillPaint)
        fillPaint.alpha = 255
    }

    private fun stroke(canvas: Canvas, path: Path, @ColorInt color: Int, width: Float, alpha: Float = 1f) {
        linePaint.color = color
        linePaint.alpha = (alpha * 255).toInt()
        linePaint.strokeWidth = width
        canvas.drawPath(path, linePaint)
    }

    private companion object {
        /** The scene's size in its own units; it scales to fit the view and stays centered. */
        const val WIDTH = 240f
        const val HEIGHT = 120f

        /** Seconds per stride. */
        const val STRIDE = 0.42f

        /** The moment drawn when animations are off: mid-sniff while waiting, legs reaching while running. */
        const val STILL = 0.105f

        const val LEG = 25f

        val STEAM_X = floatArrayOf(160f, 172f, 184f)

        /** A quick dip and return, 0.25 s long, starting at [start] seconds into the cycle. */
        fun pulse(t: Float, start: Float): Float {
            val x = (t - start) / 0.25f
            return if (x < 0f || x > 1f) 0f else sin(PI.toFloat() * x).let { it * it }
        }

        val GROUND = catPath("M28 112.5 L214 112.5")
        val TAIL_SIT = catPath("M74 104 C56 108 42 100 40 86 C39 78 44 74 48 77")
        val BODY_SIT = catPath("M94 54 C108 58 116 84 114 108 L66 108 C60 88 70 62 94 54 Z")
        val LEG_LINE = catPath("M99 82 C100 92 101 100 102 108")
        val EAR_SIT_LEFT = catPath("M90 33 L94 17 L103 28 Z")
        val EAR_SIT_RIGHT = catPath("M110 28 L120 17 L122 33 Z")
        val CLOSED_EYES = catPath("M102.4 43.8 Q105 40.6 107.6 43.8 M113.4 43.8 Q116 40.6 118.6 43.8")
        val NOSE_SIT = catPath("M122 47.6 L126.2 47.6 L124.1 50.2 Z")
        val MOUTH = catPath("M120.6 52.4 Q122.4 54.4 124.2 52.4 Q126 54.4 127.8 52.4")
        val WHISKERS_SIT = catPath("M127 51 L139 49 M127 53.6 L138 56")
        val STEAM = catPath("M0 0 C-5 -6 5 -11 0 -18 C-5 -25 5 -30 0 -36")
        val NOODLES = catPath("M146 86 C150 78 156 78 160 82 C164 77 170 77 174 81 C178 76 186 77 194 86 Z")
        val STRANDS = catPath("M152 84 C155 81 158 81 161 84 M165 83 C168 80 171 80 174 83 M178 83 C181 80 185 80 188 84")
        val CHOPSTICKS = catPath("M168 83 L208 61 M172 86 L212 65")
        val BOWL = catPath("M140 86 L200 86 C198 102 186 110 170 110 C154 110 142 102 140 86 Z")
        val RIM = catPath("M143 91 L197 91")
        val TAIL_RUN = catPath("M90 66 C80 60 72 64 64 57 C58 51 54 52 49 47")
        val BODY_RUN = catPath("M88 64 C100 57 130 56 142 63 C149 67 148 77 138 80 C124 84 104 84 92 81 C83 78 80 68 88 64 Z")
        val EAR_RUN_LEFT = catPath("M146 48 L141 35 L153 42 Z")
        val EAR_RUN_RIGHT = catPath("M156 44 L158 31 L166 42 Z")
        val NOSE_RUN = catPath("M168.6 57.6 L172 57.6 L170.3 59.8 Z")
        val WHISKERS_RUN = catPath("M172 59.6 L182 57.6 M172 61.6 L181 63.6")
    }
}

/** The commands of SVG path data in absolute M, L, Q, C and Z form, the subset the cat is drawn with. */
internal fun pathCommands(data: String): List<Pair<Char, FloatArray>> {
    val tokens = Regex("""[MLQCZ]|-?(?:\d+\.?\d*|\.\d+)""").findAll(data).map { it.value }.toList()
    val commands = ArrayList<Pair<Char, FloatArray>>()
    var i = 0
    var command = 'M'
    while (i < tokens.size) {
        if (tokens[i][0].isLetter()) command = tokens[i++][0]
        val count = when (command) {
            'M', 'L' -> 2
            'Q' -> 4
            'C' -> 6
            'Z' -> 0
            else -> error("unsupported path command $command")
        }
        require(i + count <= tokens.size && (i until i + count).none { tokens[it][0].isLetter() }) { "truncated path data: $data" }
        commands += command to FloatArray(count) { tokens[i + it].toFloat() }
        i += count
        // Coordinates after a move continue the line, as in SVG.
        if (command == 'M') command = 'L'
        if (command == 'Z' && i < tokens.size && !tokens[i][0].isLetter()) error("coordinates after Z: $data")
    }
    return commands
}

private fun catPath(data: String) = Path().apply {
    for ((command, v) in pathCommands(data)) {
        when (command) {
            'M' -> moveTo(v[0], v[1])
            'L' -> lineTo(v[0], v[1])
            'Q' -> quadTo(v[0], v[1], v[2], v[3])
            'C' -> cubicTo(v[0], v[1], v[2], v[3], v[4], v[5])
            else -> close()
        }
    }
}
