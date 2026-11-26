package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.core.graphics.createBitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.nekobyte.nekobox.R
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

// The Full-bleed icon's art drawn with real graphics: option A (1.15 times the classic art, 6 of 108 dp lower) at every
// size, against the classic art placed by hand.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "xxhdpi")
class LauncherIconArtTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val density get() = context.resources.displayMetrics.density

    private fun render(drawable: Drawable, px: Int): Bitmap = createBitmap(px, px).also {
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(it))
    }

    // Option A placed by hand on a layer [px] wide: the classic art 1.15 times as large, centred, 6/108 lower.
    private fun optionA(px: Int): Bitmap {
        val art = context.getDrawable(R.mipmap.ic_launcher_foreground)!!
        val size = px * 1.15f
        val left = (px - size) / 2
        val top = left + px * 6f / 108
        return createBitmap(px, px).also {
            art.setBounds(left.roundToInt(), top.roundToInt(), (left + size).roundToInt(), (top + size).roundToInt())
            art.draw(Canvas(it))
        }
    }

    // The mean difference of two bitmaps over every channel, 0 to 255.
    private fun difference(a: Bitmap, b: Bitmap): Double {
        var sum = 0L
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum += abs(Color.alpha(p) - Color.alpha(q)) + abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            }
        }
        return sum / (a.width * a.height * 4.0)
    }

    @Test
    fun theArtIsOptionAAtEverySize() {
        val out = File("build/icon-renders").apply { mkdirs() }
        // Icons of 48, 64 and 108 dp: the foreground layer is 1.5 times the icon.
        for (iconDp in listOf(48, 64, 108)) {
            val layer = (iconDp * 1.5f * density).roundToInt()
            val drawn = render(context.getDrawable(R.drawable.ic_launcher_foreground_full_bleed)!!, layer)
            val expected = optionA(layer)
            val diff = difference(drawn, expected)
            drawn.compress(Bitmap.CompressFormat.PNG, 100, File(out, "full-bleed-foreground-$iconDp.png").outputStream())
            assertTrue("$iconDp dp icon: $diff", diff < 1.5)
            val icon = context.getDrawable(R.mipmap.ic_launcher_full_bleed) as AdaptiveIconDrawable
            render(icon, (iconDp * density).roundToInt()).compress(Bitmap.CompressFormat.PNG, 100, File(out, "full-bleed-icon-$iconDp.png").outputStream())
        }
    }

    @Test
    fun atTheSizeItWasMeasuredAtItMatchesTheFixedLayersItReplaces() {
        // The former layer-list: 124.2 dp art, centred under a 12 dp top inset, right only on a 108 dp layer.
        val layer = (108 * density).roundToInt()
        val former = LayerDrawable(arrayOf(context.getDrawable(R.mipmap.ic_launcher_foreground)!!)).apply {
            setLayerSize(0, (124.2f * density).roundToInt(), (124.2f * density).roundToInt())
            setLayerGravity(0, Gravity.CENTER)
            setLayerInsetTop(0, (12 * density).roundToInt())
        }
        val diff = difference(render(context.getDrawable(R.drawable.ic_launcher_foreground_full_bleed)!!, layer), render(former, layer))
        assertTrue("$diff", diff < 1.5)
        // On a 72 dp layer (a 48 dp icon) the fixed sizes were off.
        val small = (72 * density).roundToInt()
        assertTrue(difference(render(former, small), optionA(small)) > 5)
    }
}
