package xyz.nekobyte.nekobox.widget

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.databinding.LayoutSubscriptionQrBinding
import xyz.nekobyte.nekobox.ktx.EaseOut
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.animationsOn
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ui.ThemedActivity
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** A subscription's link as it shares: the provider's own http(s) address, which any client imports. */
fun ProxyGroup.subscriptionLink(): String? = subscription?.link?.trim()?.takeIf {
    type == GroupType.SUBSCRIPTION && (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true))
}

/**
 * The subscription QR card's own colors, on every palette, as the black hole keeps its own. The code itself stays dark
 * modules on a light ground with its full quiet zone, the way every scanner reads a QR code.
 */
internal object NeonQr {
    const val PANEL = 0xFF07070C.toInt()
    const val CYAN = 0xFF00F0FF.toInt()
    const val MAGENTA = 0xFFFF2A6D.toInt()
    const val YELLOW = 0xFFFCEE0A.toInt()
    const val INK = 0xFF0A0A0F.toInt()

    /** The code of [text], one cell a module, with the standard four-module quiet zone. */
    fun encode(text: String): BitMatrix {
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** The pixels of [code], [scale] to a module, row by row: ink modules on yellow. */
    fun pixels(code: BitMatrix, scale: Int = 1): IntArray {
        val side = code.width * scale
        return IntArray(side * side) { index -> if (code[index % side / scale, index / side / scale]) INK else YELLOW }
    }
}

/** The card: a panel with cut corners, faint scanlines and a neon border split into cyan and magenta. */
class CyberCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val outline = Path()
    private val shifted = Path()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NeonQr.PANEL }
    private val lines = Paint().apply {
        color = Color.WHITE
        alpha = 12
        strokeWidth = density
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The border's glow needs room inside the view; the content's padding keeps clear of it.
        val edge = 6 * density
        val cut = 18 * density
        val right = w - edge
        val bottom = h - edge
        outline.reset()
        outline.moveTo(edge + cut, edge)
        outline.lineTo(right, edge)
        outline.lineTo(right, bottom - cut)
        outline.lineTo(right - cut, bottom)
        outline.lineTo(edge, bottom)
        outline.lineTo(edge, edge + cut)
        outline.close()
        shifted.set(outline)
        shifted.offset(2 * density, 2 * density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(outline, panel)
        // Measured here: inside withClip, density and width would be the canvas's own.
        val step = 4 * density
        val right = width.toFloat()
        val bottom = height.toFloat()
        canvas.withClip(outline) {
            var y = step
            while (y < bottom) {
                drawLine(0f, y, right, y, lines)
                y += step
            }
        }
        // The magenta copy sits off the cyan line, as a glitch splits a picture's colors.
        stroke.color = NeonQr.MAGENTA
        stroke.alpha = 200
        stroke.strokeWidth = 1.5f * density
        canvas.drawPath(shifted, stroke)
        // Neon: wide faint lines under a bright thin one.
        stroke.color = NeonQr.CYAN
        for ((width, alpha) in listOf(9f to 18, 5f to 40, 2.5f to 110, 1.5f to 255)) {
            stroke.strokeWidth = width * density
            stroke.alpha = alpha
            canvas.drawPath(outline, stroke)
        }
    }
}

/** A title split into magenta and cyan behind its own color, by [split] pixels each way. */
class GlitchText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private val copies = listOf(PorterDuffColorFilter(NeonQr.MAGENTA, PorterDuff.Mode.SRC_IN) to -1f, PorterDuffColorFilter(NeonQr.CYAN, PorterDuff.Mode.SRC_IN) to 1f)

    var split = 2 * resources.displayMetrics.density
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val text = layout
        if (text != null && split != 0f) {
            // Color glyphs too, emoji among them, take each copy's color whole.
            val filter = paint.colorFilter
            for ((tint, side) in copies) {
                paint.colorFilter = tint
                canvas.withTranslation(compoundPaddingLeft + side * split, extendedPaddingTop.toFloat()) { text.draw(this) }
            }
            paint.colorFilter = filter
        }
        super.onDraw(canvas)
    }
}

/** The QR code, ink on yellow and sharp to the pixel, framed by cyan corner marks. */
class NeonQrView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val maxSide = (280 * density).toInt()
    private var code: Bitmap? = null
    private val ground = Paint().apply { color = NeonQr.YELLOW }
    private val sharp = Paint().apply { isFilterBitmap = false }
    private val marks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NeonQr.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val target = Rect()

    /** Shows the code of [text]; throws [WriterException] when it does not fit in a QR code. */
    fun show(text: String) {
        val matrix = NeonQr.encode(text)
        code = Bitmap.createBitmap(NeonQr.pixels(matrix), matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val side = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxSide else min(available, maxSide)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val code = code ?: return
        // The corner marks keep 10 dp around the yellow square.
        val gap = (10 * density).toInt()
        val side = width - 2 * gap
        canvas.drawRect(gap.toFloat(), gap.toFloat(), (gap + side).toFloat(), (gap + side).toFloat(), ground)
        // Whole pixels a module, so every module keeps one size; what is left stays yellow around it.
        val module = side / code.width
        val drawn = module * code.width
        val start = gap + (side - drawn) / 2
        target.set(start, start, start + drawn, start + drawn)
        canvas.drawBitmap(code, null, target, sharp)
        val inset = 4 * density
        val arm = 20 * density
        val near = gap - inset
        val far = gap + side + inset
        for ((x, dx) in listOf(near to 1, far to -1)) {
            for ((y, dy) in listOf(near to 1, far to -1)) {
                canvas.drawLine(x, y, x + dx * arm, y, marks)
                canvas.drawLine(x, y, x, y + dy * arm, marks)
            }
        }
    }
}

/**
 * A subscription's link as a QR code on its cyberpunk card, for another device or person to scan: whoever does gets
 * the subscription, which the card says.
 */
class SubscriptionQrDialog() : DialogFragment() {
    constructor(link: String, name: String) : this() {
        arguments = bundleOf(KEY_LINK to link, KEY_NAME to name)
    }

    private var binding: LayoutSubscriptionQrBinding? = null
    private var shown = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val name = requireArguments().getString(KEY_NAME).orEmpty()
        val binding = LayoutSubscriptionQrBinding.inflate(inflater, container, false)
        binding.name.text = name
        binding.code.contentDescription = getString(R.string.subscription_qr_description, name)
        try {
            binding.code.show(requireArguments().getString(KEY_LINK).orEmpty())
        } catch (e: WriterException) {
            Logs.w(e)
            (activity as? ThemedActivity)?.snackbar(e.readableMessage)?.show()
            dismissAllowingStateLoss()
            return null
        }
        this.binding = binding
        shown = savedInstanceState != null
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        dialog.setTitle(requireArguments().getString(KEY_NAME))
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val margin = (24 * resources.displayMetrics.density).toInt()
            val width = min(resources.displayMetrics.widthPixels - 2 * margin, (360 * resources.displayMetrics.density).toInt())
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (!shown) powerOn()
        shown = true
    }

    // The card switches on as an old screen does: a bright line widens, then opens to the card's height, and the
    // title's colors settle. The opening keeps Material's standard curve: an exponential one would be done before the
    // first frames show it.
    private fun powerOn() {
        val binding = binding ?: return
        if (!requireContext().animationsOn()) return
        val card = binding.root
        val settled = binding.name.split
        val line = 0.02f
        card.scaleX = 0.4f
        card.scaleY = line
        card.alpha = 0f
        binding.name.split = settled * 5
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).setDuration(60),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 0.4f, 1f).setDuration(110).apply { interpolator = EaseOut },
                ObjectAnimator.ofFloat(card, View.SCALE_Y, line, 1f).apply {
                    startDelay = 90
                    duration = 240
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                },
                ValueAnimator.ofFloat(settled * 5, settled).apply {
                    startDelay = 200
                    duration = 420
                    interpolator = EaseOut
                    addUpdateListener { binding.name.split = it.animatedValue as Float }
                },
            )
            start()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_LINK = "link"
        const val KEY_NAME = "name"
    }
}
