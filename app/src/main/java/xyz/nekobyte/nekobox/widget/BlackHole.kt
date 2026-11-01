package xyz.nekobyte.nekobox.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The black hole at the heart of the connect button: its horizon, the lensed halo of its accretion disk, and the disk
 * itself seen nearly edge on, the far half behind the horizon and the near half across it. The disk goes from orange
 * to blue with the hue, and its streaks turn with the spin. A burst throws jets along the poles, an X-ray ring out to
 * the rim and a flash at the core. Sizes are fractions of the orb's radius. The disk comes from textures made once per
 * size: a still glow, brighter on the side coming towards us, and the streaks, which turn; one pair a color.
 */
internal class BlackHole {
    /** Whether the orb sits on a dark theme: light ones take the saturated end of each color for what glows. */
    var dark = true

    private var made = 0f
    private val glow = arrayOfNulls<Bitmap>(2)
    private val streaks = arrayOfNulls<Bitmap>(2)
    private val halos = arrayOfNulls<Shader>(2)
    private var flashShader: Shader? = null
    private var jetShader: Shader? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val matrix = Matrix()

    // A jet pointing up from the origin: unit length, unit width at its base.
    private val jet = Path().apply {
        moveTo(-0.5f, 0f)
        quadTo(-0.12f, -0.55f, 0f, -1f)
        quadTo(0.12f, -0.55f, 0.5f, 0f)
        close()
    }

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, hue: Float, spin: Float, burst: Float) {
        if (radius <= 0f) return
        if (made != radius) make(radius)
        canvas.withTranslation(cx, cy) {
            rotate(TILT)
            // The far half of the disk, the halo the horizon bends its light into, the horizon, then the near half.
            withClip(-radius, -radius, radius, 0f) { disk(this, hue, spin) }
            for (i in 0..1) {
                val share = if (i == 0) 1 - hue else hue
                if (share < 0.01f) continue
                stroke.shader = halos[i]
                stroke.strokeWidth = radius * HALO_WIDTH
                stroke.alpha = (share * 255).toInt()
                drawCircle(0f, 0f, radius * HALO, stroke)
            }
            stroke.shader = null
            drawCircle(0f, 0f, radius * HORIZON, horizon)
            withClip(-radius, 0f, radius, radius) { disk(this, hue, spin) }
            if (burst in 0f..1f) jets(this, radius, burst)
        }
        if (burst in 0f..1f) rings(canvas, cx, cy, radius, burst)
    }

    private fun disk(canvas: Canvas, hue: Float, spin: Float) {
        for (i in 0..1) {
            val share = if (i == 0) 1 - hue else hue
            if (share < 0.01f) continue
            bitmapPaint.alpha = (share * 255).toInt()
            // Each color's textures are made the first time it shows: off needs only the orange.
            val bitmap = glow[i] ?: texture(i, still = true).also { glow[i] = it }
            val turning = streaks[i] ?: texture(i, still = false).also { streaks[i] = it }
            val half = bitmap.width / 2f
            matrix.setTranslate(-half, -half)
            matrix.postScale(1f, ASPECT)
            canvas.drawBitmap(bitmap, matrix, bitmapPaint)
            matrix.setTranslate(-half, -half)
            matrix.postRotate(spin)
            matrix.postScale(1f, ASPECT)
            canvas.drawBitmap(turning, matrix, bitmapPaint)
        }
    }

    // Jets along the poles: out fast, a moment held, then gone.
    private fun jets(canvas: Canvas, radius: Float, burst: Float) {
        val out = if (burst < 0.2f) 1 - (1 - burst / 0.2f).pow(3) else 1f
        val fade = if (burst < 0.4f) 1f else max(0f, 1 - (burst - 0.4f) / 0.5f)
        if (fade <= 0f) return
        val length = radius * (0.2f + 0.68f * out)
        fill.shader = jetShader
        for (direction in floatArrayOf(1f, -1f)) {
            canvas.withScale(1f, direction) {
                // A wide faint sheath, then the bright core.
                fill.alpha = (0.28f * fade * 255).toInt()
                withScale(radius * 0.24f, length) { drawPath(jet, fill) }
                fill.alpha = (fade * 255).toInt()
                withScale(radius * 0.07f, length) { drawPath(jet, fill) }
            }
        }
        fill.shader = null
    }

    // The X-ray front running out to the rim, a fainter echo behind it, and the flash at the core.
    private fun rings(canvas: Canvas, cx: Float, cy: Float, radius: Float, burst: Float) {
        val run = 1 - (1 - min(1f, burst / 0.6f)).pow(3)
        val strength = (1 - run).pow(0.7f) * (if (burst < 0.6f) 1f else max(0f, 1 - (burst - 0.6f) / 0.3f))
        if (strength > 0f) {
            val front = radius * (HORIZON + (0.95f - HORIZON) * run)
            stroke.color = if (dark) XRAY else BLUE_EDGE
            stroke.strokeWidth = radius * 0.05f
            stroke.alpha = (strength * 255).toInt()
            canvas.drawCircle(cx, cy, front, stroke)
            stroke.strokeWidth = radius * 0.022f
            stroke.alpha = (0.3f * strength * 255).toInt()
            canvas.drawCircle(cx, cy, front * 0.72f, stroke)
        }
        val flash = if (burst < 0.1f) burst / 0.1f else max(0f, 1 - (burst - 0.1f) / 0.35f)
        if (flash > 0f) {
            fill.shader = flashShader
            fill.alpha = (flash * 255).toInt()
            val reach = radius * (0.22f + 0.45f * min(1f, burst / 0.45f))
            canvas.withTranslation(cx, cy) { withScale(reach, reach) { drawCircle(0f, 0f, 1f, fill) } }
            fill.shader = null
        }
    }

    private fun make(radius: Float) {
        made = radius
        glow.fill(null)
        streaks.fill(null)
        for ((i, colors) in listOf(ORANGE, BLUE).withIndex()) {
            // Brightest over the top, where the far side of the disk shows bent around the horizon.
            val top = if (dark) blend(colors[1], colors[2], 0.45f) else colors[1]
            val bottom = if (dark) colors[1] else colors[0]
            halos[i] = LinearGradient(
                0f,
                -radius * HALO,
                0f,
                radius * HALO,
                intArrayOf(withAlpha(top, 0.9f), withAlpha(bottom, 0.4f), withAlpha(bottom, 0.14f)),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        flashShader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, withAlpha(if (dark) XRAY else BLUE_MID, 0.5f), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        jetShader = LinearGradient(0f, 0f, 0f, -1f, intArrayOf(if (dark) JET else BLUE_EDGE, withAlpha(if (dark) XRAY else BLUE_MID, 0.6f), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    /**
     * One color's disk in its own plane, centred in a square. The still glow falls off outwards and is brighter on
     * the left, the side turning towards us; the streaks are what turns.
     */
    private fun texture(palette: Int, still: Boolean): Bitmap {
        val radius = made
        val colors = if (palette == 0) ORANGE else BLUE
        val side = max(2, (radius * OUTER * 2).toInt() + 2)
        val pixels = IntArray(side * side)
        val centre = side / 2f
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dx = (x + 0.5f - centre) / radius
                val dy = (y + 0.5f - centre) / radius
                val r = sqrt(dx * dx + dy * dy)
                val f = (r - INNER) / (OUTER - INNER)
                val body = if (f in 0f..1f) (1 - f).pow(1.5f) * min(1f, f / 0.06f) else 0f
                val intensity = if (still) {
                    val halo = if (r > INNER * 0.8f) max(0f, 1 - abs(f - 0.15f) / 0.9f).pow(2) * 0.35f * (if (f in 0f..1f) 0.5f else 1f) else 0f
                    (body * 0.85f + halo) * (1 + 0.45f * (-dx / OUTER))
                } else {
                    val theta = atan2(dy, dx)
                    val rd = r * 36
                    body * 0.5f * (0.5f + 0.5f * (0.6f * sin(5 * theta + 0.35f * rd) + 0.4f * sin(11 * theta - 0.6f * rd)))
                }
                pixels[y * side + x] = shade(colors, intensity)
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    // Edge to middle to core with [intensity], and as opaque as it is bright.
    private fun shade(colors: IntArray, intensity: Float): Int {
        val t = intensity.coerceIn(0f, 1f)
        val low = t < 0.5f
        val from = if (low) colors[0] else colors[1]
        val to = if (low) colors[1] else colors[2]
        val k = if (low) t * 2 else t * 2 - 1
        val alpha = (min(1f, intensity * 1.15f) * 255).toInt()
        return Color.argb(
            alpha,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt(),
        )
    }

    private fun blend(from: Int, to: Int, k: Float) = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt(),
    )

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        // Edge, middle and core of each disk; the blue is the light of a connected orb.
        private val ORANGE = intArrayOf(Color.rgb(176, 58, 22), Color.rgb(255, 152, 56), Color.rgb(255, 240, 200))
        private val BLUE = intArrayOf(Color.rgb(38, 72, 206), Color.rgb(92, 182, 255), Color.rgb(232, 246, 255))
        val BLUE_EDGE = BLUE[0]
        val BLUE_MID = BLUE[1]
        private val XRAY = Color.rgb(210, 236, 255)
        private val JET = Color.rgb(235, 248, 255)

        private const val HORIZON = 6.5f / 36
        private const val HALO = 8.6f / 36
        private const val HALO_WIDTH = 1.25f / 36
        private const val INNER = 9.4f / 36
        private const val OUTER = 23.5f / 36
        private const val ASPECT = 0.3f

        // The disk leans a little, as one seen from just above its plane would.
        private const val TILT = -9f
    }
}
