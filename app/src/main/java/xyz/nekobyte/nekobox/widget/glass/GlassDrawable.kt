package xyz.nekobyte.nekobox.widget.glass

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.Logs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where glass sits in its drawable: [radius] rounds the corners ([pill] makes them half the short
 * side), [inset] keeps it off the bounds ([insetPaddingBottom] also off the host's bottom padding),
 * and [open] edges continue past the view, like a bar's screen edges, so they get no rim and no
 * refraction.
 */
class GlassShape(
    val radius: Float = 0f,
    val pill: Boolean = false,
    val inset: Float = 0f,
    val insetPaddingBottom: Boolean = false,
    val open: Int = 0,
) {
    companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8

        val BAR = GlassShape(open = LEFT or TOP or RIGHT)
        val DRAWER = GlassShape(open = LEFT or TOP or BOTTOM)
        val CIRCLE = GlassShape(pill = true)
        fun capsule(inset: Float, radius: Float) = GlassShape(radius = radius, inset = inset, insetPaddingBottom = true)
    }
}

/**
 * The liquid glass material for [host]: a tint, a highlight along the top and a rim catching light
 * from the top left. With a [backdrop] on Android 12+ the content behind is drawn first, blurred,
 * and on Android 13+ bent along the edges.
 */
class GlassDrawable(
    private val host: View,
    backdrop: GlassBackdrop?,
    @ColorInt tint: Int,
    private val shape: GlassShape,
) : Drawable() {

    @ColorInt
    var tintColor: Int = tint
        set(value) {
            if (field == value) return
            field = value
            tintPaint.color = value
            laidOut.setEmpty()
            invalidateSelf()
        }

    private val density = host.resources.displayMetrics.density
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeRect = RectF()
    private val rimRect = RectF()
    private val path = Path()
    private val rimPath = Path()
    private var radius = 0f
    private val laidOut = Rect()
    private var laidOutPadding = -1
    private val live = if (backdrop != null && Build.VERSION.SDK_INT >= 31) LiveGlass(host, backdrop, host.resources) else null

    init {
        if (backdrop != null && live != null) {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = backdrop.register(host)
                override fun onViewDetachedFromWindow(v: View) = backdrop.unregister(host)
            })
            if (host.isAttachedToWindow) backdrop.register(host)
        }
    }

    private fun layoutShape() {
        val padding = if (shape.insetPaddingBottom) host.paddingBottom else 0
        if (bounds == laidOut && padding == laidOutPadding) return
        laidOut.set(bounds)
        laidOutPadding = padding
        shapeRect.set(bounds)
        shapeRect.inset(shape.inset, shape.inset)
        shapeRect.bottom -= padding
        // Open edges reach past the view, so no corner or rim of theirs lands inside it.
        val reach = 64 * density
        if (shape.open and GlassShape.LEFT != 0) shapeRect.left -= reach
        if (shape.open and GlassShape.TOP != 0) shapeRect.top -= reach
        if (shape.open and GlassShape.RIGHT != 0) shapeRect.right += reach
        if (shape.open and GlassShape.BOTTOM != 0) shapeRect.bottom += reach
        radius = if (shape.pill) min(shapeRect.width(), shapeRect.height()) / 2 else shape.radius
        path.reset()
        path.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)
        val half = rimPaint.strokeWidth / 2
        rimRect.set(shapeRect)
        rimRect.inset(half, half)
        rimPath.reset()
        rimPath.addRoundRect(rimRect, max(0f, radius - half), max(0f, radius - half), Path.Direction.CW)

        val dark = ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(tintColor, 0xFF)) < 0.5
        if (shape.open and GlassShape.TOP != 0) {
            // A bar shows only its lower edge: a hairline.
            rimPaint.shader = null
            rimPaint.color = if (dark) 0x26FFFFFF else 0x1A000000
            shinePaint.shader = null
        } else {
            val bright = if (dark) 0x80FFFFFF.toInt() else 0xE6FFFFFF.toInt()
            val faint = if (dark) 0x12FFFFFF else 0x4DFFFFFF
            val reflected = if (dark) 0x33FFFFFF else 0x99FFFFFF.toInt()
            rimPaint.shader = LinearGradient(
                shapeRect.left,
                shapeRect.top,
                shapeRect.right,
                shapeRect.bottom,
                intArrayOf(bright, faint, reflected),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            shinePaint.shader = LinearGradient(
                0f,
                shapeRect.top,
                0f,
                shapeRect.top + shapeRect.height() / 2,
                if (dark) 0x1FFFFFFF else 0x47FFFFFF,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        layoutShape()
        canvas.withClip(bounds) {
            if (Build.VERSION.SDK_INT >= 31) live?.draw(this, shapeRect, radius, bounds, path)
            drawPath(path, tintPaint)
            if (shinePaint.shader != null) drawPath(path, shinePaint)
            drawPath(rimPath, rimPaint)
        }
    }

    override fun getOutline(outline: Outline) {
        // Floating glass casts a soft shadow; bars and the drawer leave theirs to the layout.
        if (shape.open != 0 || bounds.isEmpty) return super.getOutline(outline)
        layoutShape()
        outline.setRoundRect(
            shapeRect.left.roundToInt(),
            shapeRect.top.roundToInt(),
            shapeRect.right.roundToInt(),
            shapeRect.bottom.roundToInt(),
            radius,
        )
        outline.alpha = 0.5f
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The content behind the glass, drawn again blurred, and bent along the edges where AGSL runs. */
@RequiresApi(31)
private class LiveGlass(private val host: View, private val backdrop: GlassBackdrop, res: Resources) {
    private val node = RenderNode("glass")
    private val blur = res.getDimension(R.dimen.glass_blur)
    private val bezel = res.getDimension(R.dimen.glass_bezel)
    private val refraction = res.getDimension(R.dimen.glass_refraction)
    private val dispersion = res.getDimension(R.dimen.glass_dispersion)

    // The blur and the bend reach past the shape, so the copy covers that much more.
    private val margin = blur + refraction + dispersion
    private val lens = if (Build.VERSION.SDK_INT >= 33) Lens.create(bezel, refraction, dispersion) else null
    private val hostInWindow = IntArray(2)
    private val backdropInWindow = IntArray(2)
    private val visible = RectF()
    private val lensRect = RectF()
    private val effectRect = RectF()
    private var effectRadius = -1f

    fun draw(canvas: Canvas, shapeRect: RectF, radius: Float, bounds: Rect, clip: Path) {
        if (!canvas.isHardwareAccelerated) return
        val content = backdrop.node ?: return
        visible.set(shapeRect)
        if (!visible.intersect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())) return
        host.getLocationInWindow(hostInWindow)
        backdrop.view.getLocationInWindow(backdropInWindow)
        // Nothing lies past the window's edges, so the copy stops there and the blur clamps to the edge.
        val root = host.rootView
        val left = max(visible.left - margin, -hostInWindow[0].toFloat())
        val top = max(visible.top - margin, -hostInWindow[1].toFloat())
        val width = (min(visible.right + margin, (root.width - hostInWindow[0]).toFloat()) - left).roundToInt()
        val height = (min(visible.bottom + margin, (root.height - hostInWindow[1]).toFloat()) - top).roundToInt()
        if (width <= 0 || height <= 0) return
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        recording.translate(-hostInWindow[0] - left, -hostInWindow[1] - top)
        // The window's canvas goes under the content, so the glass hides what it covers instead of showing it sharp.
        host.rootView.background?.draw(recording)
        recording.translate(backdropInWindow[0].toFloat(), backdropInWindow[1].toFloat())
        recording.drawRenderNode(content)
        node.endRecording()
        lensRect.set(shapeRect)
        lensRect.offset(-left, -top)
        if (lensRect != effectRect || radius != effectRadius) {
            effectRect.set(lensRect)
            effectRadius = radius
            val blurred = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            node.setRenderEffect(if (Build.VERSION.SDK_INT >= 33 && lens != null) lens.effect(lensRect, radius, blurred) else blurred)
        }
        canvas.withClip(clip) {
            translate(left, top)
            drawRenderNode(node)
        }
    }
}

@RequiresApi(33)
private class Lens private constructor(private val shader: RuntimeShader) {
    fun effect(shape: RectF, radius: Float, input: RenderEffect): RenderEffect {
        shader.setFloatUniform("shapeRect", shape.left, shape.top, shape.right, shape.bottom)
        shader.setFloatUniform("radius", radius)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), input)
    }

    companion object {
        fun create(bezel: Float, refraction: Float, dispersion: Float): Lens? = try {
            Lens(
                RuntimeShader(LENS).apply {
                    setFloatUniform("bezel", bezel)
                    setFloatUniform("refraction", refraction)
                    setFloatUniform("dispersion", dispersion)
                    setFloatUniform("saturation", 1.2f)
                },
            )
        } catch (e: RuntimeException) {
            // Glass keeps its blur when the shader cannot run.
            Logs.w("Liquid glass refraction unavailable", e)
            null
        }

        /**
         * Within [bezel] of the shape's edge, samples move outward along the edge normal, most at the
         * edge itself, so what lies beyond the edge bends into view as through the rim of a lens.
         * Red and blue move a little apart, and the colors gain some saturation, as behind glass.
         */
        private const val LENS = """
uniform shader content;
uniform float4 shapeRect;
uniform float radius;
uniform float bezel;
uniform float refraction;
uniform float dispersion;
uniform float saturation;

float shapeDistance(float2 p) {
    float2 center = (shapeRect.xy + shapeRect.zw) * 0.5;
    float2 halfSize = (shapeRect.zw - shapeRect.xy) * 0.5;
    float2 q = abs(p - center) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

half4 main(float2 coord) {
    float d = shapeDistance(coord);
    half4 color;
    if (d > 0.0 || d < -bezel) {
        color = content.eval(coord);
    } else {
        float2 normal = normalize(float2(
            shapeDistance(coord + float2(1.0, 0.0)) - shapeDistance(coord - float2(1.0, 0.0)),
            shapeDistance(coord + float2(0.0, 1.0)) - shapeDistance(coord - float2(0.0, 1.0))) + 0.0001);
        float rim = 1.0 + d / bezel;
        float2 base = coord + normal * (rim * rim * refraction);
        float2 spread = normal * (rim * dispersion);
        half4 middle = content.eval(base);
        color = half4(content.eval(base + spread).r, middle.g, content.eval(base - spread).b, middle.a);
    }
    half luma = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(half3(luma), color.rgb, half(saturation));
    return color;
}
"""
    }
}
