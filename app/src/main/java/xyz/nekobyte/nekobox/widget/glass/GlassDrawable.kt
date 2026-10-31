package xyz.nekobyte.nekobox.widget.glass

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.Logs
import kotlin.math.ceil
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
 * Liquid glass for [host]: floating panes that bend and frost what lies behind them, flat with a
 * hairline rim, and on light canvases a soft edge that sets them off the content, plus an optional
 * scroll edge band.
 * [place] lays out the panes for the drawable's bounds on every frame. With a [backdrop] on
 * Android 13+ the glass refracts the live content; elsewhere it is a tinted pane over the canvas.
 * Glass that [sweeps] moves over content that stays still, as the drawer does. A [source] that
 * names a backdrop shows that one instead, while [backdrop] still tells the glass when to draw again.
 */
class GlassDrawable(
    internal val host: View,
    backdrop: GlassBackdrop?,
    @ColorInt tint: Int,
    sweeps: Boolean = false,
    source: () -> GlassBackdrop? = { null },
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
    private val canvasColor = Glass.canvasColor(host.context)
    private val dark = ColorUtils.calculateLuminance(canvasColor) < 0.5
    private val live = if (backdrop != null && Build.VERSION.SDK_INT >= 33) LiveGlass.create(host, backdrop, source, res, sweeps) else null

    // Flat paint only: a tint, a hairline rim, and on light canvases a soft edge, as thin glass shows.
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (dark) 0x33FFFFFF else 0xCCFFFFFF.toInt()
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x24000000
    }

    // A pressed pane brightens evenly.
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pressAlpha = if (dark) 0x1F else 0x47
    private val bandPaint = Paint()
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
        pane.anchor?.let { presses[it] }?.let { press ->
            if (press.amount > 0f) {
                pressPaint.alpha = (press.amount.coerceIn(0f, 1f) * pressAlpha).roundToInt()
                canvas.drawPath(path, pressPaint)
            }
        }
        // On a light canvas a soft edge just outside sets the pane off whatever lies behind it.
        if (!dark) {
            val edge = edgePaint.strokeWidth / 2
            ring.set(rect)
            ring.inset(-edge, -edge)
            canvas.drawRoundRect(ring, radius + edge, radius + edge, edgePaint)
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
    }
}

/** The two effects live glass draws with, the frost's blur and the glass shader, for [WarmUp] to draw once ahead of a first frame. */
@RequiresApi(33)
internal fun liveGlassEffects(res: Resources): List<RenderEffect> {
    val blur = res.getDimension(R.dimen.glass_blur) * LiveGlass.FROST
    return listOf(
        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
        RenderEffect.createRuntimeShaderEffect(LiveGlass.shader(res), "content"),
    )
}

/**
 * The content behind glass, frosted once a frame for all the glass in front of it: everything the glass shows is
 * frosted, so the copy is drawn and blurred at [LiveGlass.FROST] of the window's size, a sixteenth of the pixels,
 * and each glass's shader reads it at full size, so the rims stay sharp. It holds the parts of the window that
 * the glass on show reaches. One copy and one blur for all the glass on a screen spare the GPU render passes,
 * which cost phones more than the pixels do.
 */
@RequiresApi(33)
internal class SharedFrost(res: Resources) {
    private val blur = res.getDimension(R.dimen.glass_blur) * LiveGlass.FROST
    private val node = RenderNode("glass frost").apply {
        setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
    }
    private val parts = HashMap<View, RectF>()
    private var made = -1L
    private val window = IntArray(4)

    /**
     * The frost, made again when [backdrop] changed or a glass reaches past it: [host]'s glass reaches [part],
     * in window coordinates. Parts of glass no longer on show drop out.
     */
    fun covering(host: View, part: RectF, backdrop: GlassBackdrop, content: RenderNode, root: View, backdropInWindow: IntArray): RenderNode {
        var stale = made != backdrop.changes || window[0] != root.width || window[1] != root.height ||
            window[2] != backdropInWindow[0] || window[3] != backdropInWindow[1]
        if (parts.keys.removeAll { it !== host && !it.isShown }) stale = true
        if (parts[host] != part) {
            parts[host] = RectF(part)
            stale = true
        }
        if (!stale) return node
        made = backdrop.changes
        window[0] = root.width
        window[1] = root.height
        window[2] = backdropInWindow[0]
        window[3] = backdropInWindow[1]
        val width = ceil(root.width * LiveGlass.FROST).toInt()
        val height = ceil(root.height * LiveGlass.FROST).toInt()
        node.setPosition(0, 0, width, height)
        val copy = node.beginRecording(width, height)
        copy.scale(LiveGlass.FROST, LiveGlass.FROST)
        for (reach in parts.values) {
            copy.withClip(reach) {
                // The window's canvas goes under the content, so the glass hides what it covers instead of showing it sharp.
                root.background?.draw(this)
                withTranslation(backdropInWindow[0].toFloat(), backdropInWindow[1].toFloat()) { drawRenderNode(content) }
            }
        }
        node.endRecording()
        return node
    }
}

/** The content behind the glass, frosted and bent at the rims of the panes, and faded into the canvas in the band. */
@RequiresApi(33)
private class LiveGlass private constructor(
    private val host: View,
    private val backdrop: GlassBackdrop,
    private val source: () -> GlassBackdrop?,
    private val res: Resources,
    private val shader: RuntimeShader,
    private val sweeps: Boolean,
) {
    private val node = RenderNode("glass")

    // The frost and the bend reach past the panes, so the copy covers that much more.
    private val margin = res.getDimension(R.dimen.glass_blur) + res.getDimension(R.dimen.glass_refraction)
    private val hostInWindow = IntArray(2)
    private val backdropInWindow = IntArray(2)
    private val area = RectF()
    private val part = RectF()
    private var frost: RenderNode? = null
    private val panes = FloatArray(4 * MAX_PANES)
    private val radii = FloatArray(MAX_PANES)
    private val band = FloatArray(4)
    private val applied = FloatArray(panes.size + radii.size + band.size + 1)
    private val pending = FloatArray(applied.size)
    private var effectSet = false

    fun draw(canvas: Canvas, layout: GlassLayout, bounds: Rect, @ColorInt canvasColor: Int): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        // A backdrop that has not drawn yet, as a screen on its first frame, has nothing to show.
        val backdrop = source()?.takeIf { it.node != null } ?: backdrop
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
        // Glass that sweeps shows any part of the window as it moves.
        if (sweeps) {
            part.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        } else {
            part.set(left, top, left + width, top + height)
            part.offset(hostInWindow[0].toFloat(), hostInWindow[1].toFloat())
        }
        val shared = backdrop.frost ?: SharedFrost(res).also { backdrop.frost = it }
        frost = shared.covering(host, part, backdrop, content, root, backdropInWindow)
        // Square panes and no band: the shader would show the frost unchanged, so it is drawn as it is.
        if (edge == GlassBand.NONE && (0 until count).all { layout.panes[it].radius == 0f }) {
            for (i in 0 until count) canvas.withClip(layout.panes[i].rect) { drawFrost(this) }
            return true
        }
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        recording.translate(-left, -top)
        drawFrost(recording)
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
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            effectSet = true
        }
        canvas.withTranslation(left, top) { drawRenderNode(node) }
        return true
    }

    /** The shared frost lies in window coordinates. */
    private fun drawFrost(canvas: Canvas) = canvas.withTranslation(-hostInWindow[0].toFloat(), -hostInWindow[1].toFloat()) {
        withScale(1 / FROST, 1 / FROST, 0f, 0f) { frost?.let(::drawRenderNode) }
    }

    private fun FloatArray.set(a: Float, b: Float, c: Float, d: Float) {
        this[0] = a
        this[1] = b
        this[2] = c
        this[3] = d
    }

    companion object {
        const val MAX_PANES = 4

        /** The scale of the frosted copy: a 12 dp blur stays about 8 px wide there on a 440 dpi screen. */
        const val FROST = 0.25f

        fun create(host: View, backdrop: GlassBackdrop, source: () -> GlassBackdrop?, res: Resources, sweeps: Boolean): LiveGlass? = try {
            LiveGlass(host, backdrop, source, res, shader(res), sweeps)
        } catch (e: RuntimeException) {
            // The panes keep their tint and rim when the shader cannot run.
            Logs.w("Liquid glass unavailable", e)
            null
        }

        fun shader(res: Resources) = RuntimeShader(LIQUID).apply {
            setFloatUniform("bezel", res.getDimension(R.dimen.glass_bezel))
            setFloatUniform("refraction", res.getDimension(R.dimen.glass_refraction))
            setFloatUniform("dims", 0.62f, 0.4f)
        }

        /**
         * Inside a pane, samples near the rim move outward along the edge normal, most at the edge,
         * so what lies beyond bends into view as through the rim of a lens, its colors unchanged.
         * In the band, the frosted content fades into the
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
            color = content.eval(p + normal * (rim * rim * refraction));
        }
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
