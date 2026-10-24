package xyz.nekobyte.nekobox.widget.glass

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.Logs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A pane of glass in its host's coordinates. With an [anchor] it swells and lights up under a finger. */
class GlassPane {
    val rect = RectF()
    var radius = 0f
    var anchor: View? = null
}

/**
 * The scroll edge band: content passing under floating controls fades into the canvas there, so
 * their text stays readable while the glass stays clear. A top band covers the host from its top
 * down to [solid] and fades out over [fade] below; a bottom band covers it from [solid] down and
 * fades in over [fade] above.
 */
class GlassBand {
    var edge = NONE
        private set
    var solid = 0f
        private set
    var fade = 0f
        private set

    fun set(edge: Int, solid: Float, fade: Float) {
        this.edge = edge
        this.solid = solid
        this.fade = fade
    }

    companion object {
        const val NONE = 0
        const val TOP = 1
        const val BOTTOM = -1
    }
}

/** The panes and band a [GlassDrawable] draws, placed again on every frame. */
class GlassLayout internal constructor() {
    internal val panes = ArrayList<GlassPane>(4)
    internal var count = 0
    val band = GlassBand()
    private val rect = Rect()

    fun pane(): GlassPane {
        if (count == panes.size) panes += GlassPane()
        return panes[count++].also {
            it.anchor = null
            it.radius = 0f
        }
    }

    /**
     * A pane over [anchor], a descendant of [host], [insetX] and [insetY] inside it: a circle when
     * [circle], else a capsule. Hidden anchors get none.
     */
    fun paneOver(host: ViewGroup, anchor: View, insetX: Float, insetY: Float, circle: Boolean = false) {
        if (!anchor.isShown || anchor.width == 0 || anchor.height == 0) return
        anchor.getDrawingRect(rect)
        rect.offset(-anchor.scrollX, -anchor.scrollY)
        host.offsetDescendantRectToMyCoords(anchor, rect)
        val pane = pane()
        pane.rect.set(rect)
        pane.rect.inset(insetX, insetY)
        if (circle) {
            val half = min(pane.rect.width(), pane.rect.height()) / 2
            pane.rect.set(pane.rect.centerX() - half, pane.rect.centerY() - half, pane.rect.centerX() + half, pane.rect.centerY() + half)
        }
        pane.anchor = anchor
        pane.followSwell()
        pane.radius = min(pane.rect.width(), pane.rect.height()) / 2
    }
}

/** Grows the pane with its anchor's press swell, about the pane's center. */
internal fun GlassPane.followSwell() {
    val anchor = anchor ?: return
    if (anchor.scaleX == 1f && anchor.scaleY == 1f) return
    val cx = rect.centerX()
    val cy = rect.centerY()
    val halfW = rect.width() / 2 * anchor.scaleX
    val halfH = rect.height() / 2 * anchor.scaleY
    rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/**
 * Liquid glass for [host]: floating panes that bend and frost what lies behind them, lit along
 * their rim, with a darkened edge that sets them off the content, and an optional scroll edge band.
 * [place] lays out the panes for the drawable's bounds on every frame. With a [backdrop] on
 * Android 13+ the glass refracts the live content; elsewhere it is a tinted pane over the canvas.
 */
class GlassDrawable(
    internal val host: View,
    backdrop: GlassBackdrop?,
    @ColorInt tint: Int,
    private val place: GlassLayout.(bounds: Rect) -> Unit,
) : Drawable() {

    @ColorInt
    var tintColor: Int = tint
        set(value) {
            if (field == value) return
            field = value
            tintPaint.color = value
            invalidateSelf()
        }

    private val res = host.resources
    private val density = res.displayMetrics.density
    private val layout = GlassLayout()

    @ColorInt
    private val canvasColor = GlassAmbientDrawable.canvasColor(host.context)
    private val dark = ColorUtils.calculateLuminance(canvasColor) < 0.5

    // On the true black of an OLED theme the glass stays flat: no sheen or colored fringes, a plain rim.
    private val flat = GlassAmbientDrawable.isBlack(canvasColor)
    private val live = if (backdrop != null && Build.VERSION.SDK_INT >= 33) LiveGlass.create(host, backdrop, res, flat) else null

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }

    // The gradients are drawn in a unit square, mapped onto each pane.
    // Light falls on the upper half and leaves the lower edge in shade, so the pane reads as thick glass.
    private val shade = LinearGradient(
        0f,
        0f,
        0f,
        1f,
        intArrayOf(if (dark) 0x14FFFFFF else 0x47FFFFFF, Color.TRANSPARENT, Color.TRANSPARENT, if (dark) 0x18000000 else 0x0A000000),
        floatArrayOf(0f, 0.45f, 0.62f, 1f),
        Shader.TileMode.CLAMP,
    )

    // The specular rim: bright where the light comes from, a softer reflection across from it.
    private val specular = LinearGradient(
        0f,
        0f,
        1f,
        1f,
        intArrayOf(if (dark) 0xA6FFFFFF.toInt() else 0xFFFFFFFF.toInt(), if (dark) 0x14FFFFFF else 0x40FFFFFF, if (dark) 0x4DFFFFFF else 0xB3FFFFFF.toInt()),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val glow = RadialGradient(0f, 0f, 1f, if (dark) 0x47FFFFFF else 0x8CFFFFFF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shade }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        if (flat) color = FLAT_RIM else shader = specular
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (dark) 0x8C000000.toInt() else 0x29000000
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glow }
    private val bandPaint = Paint()
    private val unit = Matrix()
    private val path = Path()
    private val ring = RectF()
    private var bandKey = Float.NaN

    private val presses = HashMap<View, Press>()
    private var pressed: View? = null
    private val onScreen = IntArray(2)
    private val relayout = ViewTreeObserver.OnGlobalLayoutListener { invalidateSelf() }

    init {
        host.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = attach(backdrop)
                override fun onViewDetachedFromWindow(v: View) = detach(backdrop)
            },
        )
        if (host.isAttachedToWindow) attach(backdrop)
    }

    private fun attach(backdrop: GlassBackdrop?) {
        if (live != null) backdrop?.register(host)
        Glass.register(this)
        // Panes follow views the host does not lay out itself, such as a toolbar's buttons.
        host.viewTreeObserver.addOnGlobalLayoutListener(relayout)
    }

    private fun detach(backdrop: GlassBackdrop?) {
        backdrop?.unregister(host)
        Glass.unregister(this)
        host.viewTreeObserver.removeOnGlobalLayoutListener(relayout)
        for (press in presses.values) press.animator?.cancel()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        layout.count = 0
        layout.band.set(GlassBand.NONE, 0f, 0f)
        layout.place(bounds)
        val drewLive = Build.VERSION.SDK_INT >= 33 && live?.draw(canvas, layout, bounds, canvasColor) == true
        if (!drewLive) drawBand(canvas)
        for (i in 0 until layout.count) drawPane(canvas, layout.panes[i])
    }

    /** Without live glass the band is the canvas color fading out, dense enough to read over sharp content. */
    private fun drawBand(canvas: Canvas) {
        val band = layout.band
        if (band.edge == GlassBand.NONE) return
        val top = band.edge == GlassBand.TOP
        val from = if (top) bounds.top.toFloat() else bounds.bottom.toFloat()
        val to = if (top) band.solid + band.fade else band.solid - band.fade
        val key = from * 31 + to * 17 + band.solid
        if (key != bandKey) {
            bandKey = key
            val solid = ((band.solid - from) / (to - from)).coerceIn(0f, 1f)
            bandPaint.shader = LinearGradient(
                0f,
                from,
                0f,
                to,
                intArrayOf(ColorUtils.setAlphaComponent(canvasColor, 0xF0), ColorUtils.setAlphaComponent(canvasColor, 0xD6), Color.TRANSPARENT),
                floatArrayOf(0f, solid, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(bounds.left.toFloat(), min(from, to), bounds.right.toFloat(), max(from, to), bandPaint)
    }

    private fun drawPane(canvas: Canvas, pane: GlassPane) {
        val rect = pane.rect
        val radius = pane.radius
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, tintPaint)
        if (!flat) {
            unit.setScale(1f, rect.height())
            unit.postTranslate(0f, rect.top)
            shade.setLocalMatrix(unit)
            canvas.drawPath(path, shadePaint)
        }
        pane.anchor?.let { presses[it] }?.let { press ->
            if (press.amount > 0f) {
                // Light gathers where the finger rests.
                val reach = max(rect.height(), rect.width() / 2) * 1.2f
                unit.setScale(reach, reach)
                unit.postTranslate(press.x, press.y)
                glow.setLocalMatrix(unit)
                glowPaint.alpha = (press.amount.coerceIn(0f, 1f) * 255).roundToInt()
                canvas.withClip(path) { drawCircle(press.x, press.y, reach, glowPaint) }
            }
        }
        // The darkened edge just outside sets the pane off whatever lies behind it; black needs none.
        if (!flat) {
            val edge = edgePaint.strokeWidth / 2
            ring.set(rect)
            ring.inset(-edge, -edge)
            canvas.drawRoundRect(ring, radius + edge, radius + edge, edgePaint)
            unit.setScale(rect.width(), rect.height())
            unit.postTranslate(rect.left, rect.top)
            specular.setLocalMatrix(unit)
        }
        val rim = rimPaint.strokeWidth / 2
        ring.set(rect)
        ring.inset(rim, rim)
        canvas.drawRoundRect(ring, max(0f, radius - rim), max(0f, radius - rim), rimPaint)
    }

    /** A touch anywhere in the window; panes with an anchor answer it, nothing consumes it. */
    internal fun onTouch(event: MotionEvent) {
        host.getLocationOnScreen(onScreen)
        val x = event.rawX - onScreen[0]
        val y = event.rawY - onScreen[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Panes are placed when drawn; a hidden host or button keeps the ones from its last frame.
                if (!host.isShown) return
                for (i in 0 until layout.count) {
                    val anchor = layout.panes[i].anchor ?: continue
                    if (anchor.isShown && anchor.isEnabled && layout.panes[i].rect.contains(x, y)) {
                        pressed = anchor
                        animatePress(anchor, 1f, x, y)
                        return
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> pressed?.let { presses[it] }?.let {
                it.x = x
                it.y = y
                invalidateSelf()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed?.let {
                pressed = null
                animatePress(it, 0f, x, y)
            }
        }
    }

    private fun animatePress(anchor: View, target: Float, x: Float, y: Float) {
        val press = presses.getOrPut(anchor) { Press() }
        press.x = x
        press.y = y
        press.animator?.cancel()
        press.animator = ValueAnimator.ofFloat(press.amount, target).apply {
            // In: a quick swell that overshoots like a drop; out: an easy settle.
            duration = if (target > 0f) 240 else 360
            interpolator = if (target > 0f) OvershootInterpolator(2.5f) else DecelerateInterpolator(1.5f)
            addUpdateListener {
                press.amount = it.animatedValue as Float
                val scale = 1f + SWELL * press.amount
                anchor.scaleX = scale
                anchor.scaleY = scale
                invalidateSelf()
            }
            start()
        }
    }

    private class Press {
        var amount = 0f
        var x = 0f
        var y = 0f
        var animator: ValueAnimator? = null
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** How much a pressed pane and its content grow. */
        const val SWELL = 0.1f

        /** The rim of flat glass: a hairline, as thin glass shows on black. */
        const val FLAT_RIM = 0x33FFFFFF
    }
}

/** The content behind the glass, frosted and bent at the rims of the panes, and faded into the canvas in the band. */
@RequiresApi(33)
private class LiveGlass private constructor(private val host: View, private val backdrop: GlassBackdrop, res: Resources, private val shader: RuntimeShader) {
    private val node = RenderNode("glass")
    private val blur = res.getDimension(R.dimen.glass_blur)

    // The frost and the bend reach past the panes, so the copy covers that much more.
    private val margin = blur + res.getDimension(R.dimen.glass_refraction) + res.getDimension(R.dimen.glass_dispersion)
    private val hostInWindow = IntArray(2)
    private val backdropInWindow = IntArray(2)
    private val area = RectF()
    private val panes = FloatArray(4 * MAX_PANES)
    private val radii = FloatArray(MAX_PANES)
    private val band = FloatArray(4)
    private val applied = FloatArray(panes.size + radii.size + band.size + 1)
    private val pending = FloatArray(applied.size)
    private var effectSet = false

    fun draw(canvas: Canvas, layout: GlassLayout, bounds: Rect, @ColorInt canvasColor: Int): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        val content = backdrop.node ?: return false
        val count = min(layout.count, MAX_PANES)
        area.setEmpty()
        for (i in 0 until count) area.union(layout.panes[i].rect)
        val edge = layout.band.edge
        val solid = layout.band.solid
        val fade = layout.band.fade
        when (edge) {
            GlassBand.TOP -> area.union(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), solid + fade)
            GlassBand.BOTTOM -> area.union(bounds.left.toFloat(), solid - fade, bounds.right.toFloat(), bounds.bottom.toFloat())
        }
        if (area.isEmpty) return false
        host.getLocationInWindow(hostInWindow)
        backdrop.view.getLocationInWindow(backdropInWindow)
        // Nothing lies past the window's edges, so the copy stops there and the frost clamps to the edge.
        val root = host.rootView
        val left = max(area.left - margin, -hostInWindow[0].toFloat())
        val top = max(area.top - margin, -hostInWindow[1].toFloat())
        val width = (min(area.right + margin, (root.width - hostInWindow[0]).toFloat()) - left).roundToInt()
        val height = (min(area.bottom + margin, (root.height - hostInWindow[1]).toFloat()) - top).roundToInt()
        if (width <= 0 || height <= 0) return false
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        recording.translate(-hostInWindow[0] - left, -hostInWindow[1] - top)
        // The window's canvas goes under the content, so the glass hides what it covers instead of showing it sharp.
        root.background?.draw(recording)
        recording.translate(backdropInWindow[0].toFloat(), backdropInWindow[1].toFloat())
        recording.drawRenderNode(content)
        node.endRecording()

        panes.fill(0f)
        radii.fill(0f)
        for (i in 0 until count) {
            val rect = layout.panes[i].rect
            panes[4 * i] = rect.left - left
            panes[4 * i + 1] = rect.top - top
            panes[4 * i + 2] = rect.right - left
            panes[4 * i + 3] = rect.bottom - top
            radii[i] = layout.panes[i].radius
        }
        when (edge) {
            GlassBand.TOP -> band.set(bounds.top - top, solid - top, solid + fade - top, 1f)
            GlassBand.BOTTOM -> band.set(bounds.bottom - top, solid - top, solid - fade - top, -1f)
            else -> band.fill(0f)
        }
        panes.copyInto(pending)
        radii.copyInto(pending, panes.size)
        band.copyInto(pending, panes.size + radii.size)
        pending[pending.size - 1] = count.toFloat()
        // The effect keeps the uniforms it was made with; make a new one only when the panes move.
        if (!effectSet || !pending.contentEquals(applied)) {
            pending.copyInto(applied)
            shader.setFloatUniform("panes", panes)
            shader.setFloatUniform("radii", radii)
            shader.setFloatUniform("paneCount", count.toFloat())
            shader.setFloatUniform("band", band)
            shader.setColorUniform("canvasColor", canvasColor)
            node.setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"),
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
                ),
            )
            effectSet = true
        }
        canvas.withTranslation(left, top) { drawRenderNode(node) }
        return true
    }

    private fun FloatArray.set(a: Float, b: Float, c: Float, d: Float) {
        this[0] = a
        this[1] = b
        this[2] = c
        this[3] = d
    }

    companion object {
        const val MAX_PANES = 4

        // Flat glass bends the content without parting its colors or deepening them.
        fun create(host: View, backdrop: GlassBackdrop, res: Resources, flat: Boolean): LiveGlass? = try {
            LiveGlass(
                host,
                backdrop,
                res,
                RuntimeShader(LIQUID).apply {
                    setFloatUniform("bezel", res.getDimension(R.dimen.glass_bezel))
                    setFloatUniform("refraction", res.getDimension(R.dimen.glass_refraction))
                    setFloatUniform("dispersion", if (flat) 0f else res.getDimension(R.dimen.glass_dispersion))
                    setFloatUniform("saturation", if (flat) 1f else 1.5f)
                    setFloatUniform("dims", 0.62f, 0.4f)
                },
            )
        } catch (e: RuntimeException) {
            // The panes keep their tint and rim when the shader cannot run.
            Logs.w("Liquid glass unavailable", e)
            null
        }

        /**
         * Inside a pane, samples near the rim move outward along the edge normal, most at the edge,
         * so what lies beyond bends into view as through the rim of a lens; red and blue part a
         * little, and colors gain saturation. In the band, the frosted content fades into the
         * canvas: [dims] from the screen edge to the end of the solid part, then out over the fade.
         */
        private const val LIQUID = """
uniform shader content;
uniform float4 panes[4];
uniform float radii[4];
uniform float paneCount;
uniform float4 band;
layout(color) uniform half4 canvasColor;
uniform float2 dims;
uniform float bezel;
uniform float refraction;
uniform float dispersion;
uniform float saturation;

float paneDistance(float2 p, float4 r, float radius) {
    float2 center = (r.xy + r.zw) * 0.5;
    float2 halfSize = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - center) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

half4 main(float2 p) {
    float d = 100000.0;
    float4 r = float4(0.0);
    float radius = 0.0;
    for (int i = 0; i < 4; i++) {
        if (float(i) < paneCount) {
            float di = paneDistance(p, panes[i], radii[i]);
            if (di < d) {
                d = di;
                r = panes[i];
                radius = radii[i];
            }
        }
    }
    if (d < 0.5) {
        float b = min(bezel, max(radius, 1.0));
        half4 color;
        if (d < -b) {
            color = content.eval(p);
        } else {
            float2 normal = normalize(float2(
                paneDistance(p + float2(1.0, 0.0), r, radius) - paneDistance(p - float2(1.0, 0.0), r, radius),
                paneDistance(p + float2(0.0, 1.0), r, radius) - paneDistance(p - float2(0.0, 1.0), r, radius)) + 0.0001);
            float rim = clamp(1.0 + d / b, 0.0, 1.0);
            float2 base = p + normal * (rim * rim * refraction);
            float2 spread = normal * (rim * dispersion);
            half4 middle = content.eval(base);
            color = half4(content.eval(base + spread).r, middle.g, content.eval(base - spread).b, middle.a);
        }
        half luma = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
        color.rgb = mix(half3(luma), color.rgb, half(saturation));
        return color * half(clamp(0.5 - d, 0.0, 1.0));
    }
    if (band.w == 0.0) {
        return half4(0.0);
    }
    float into = band.w > 0.0 ? p.y - band.x : band.x - p.y;
    float solid = abs(band.y - band.x);
    float fade = abs(band.z - band.y);
    if (into < 0.0 || into > solid + fade) {
        return half4(0.0);
    }
    float alpha = into <= solid ? 1.0 : 1.0 - smoothstep(0.0, fade, into - solid);
    float dim = mix(dims.x, dims.y, clamp(into / max(solid, 1.0), 0.0, 1.0));
    half4 color = content.eval(p);
    color.rgb = mix(color.rgb, canvasColor.rgb, half(dim));
    return color * half(alpha);
}
"""
    }
}
