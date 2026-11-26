package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.nekobyte.nekobox.R
import java.util.Locale

// The connect widget's layout measured with real fonts, the size thresholds of widgetSpec against it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "xxhdpi")
class ConnectWidgetLayoutTest {
    private fun context(fontScale: Float, locale: Locale = Locale.US): Context {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(locale)
        }
        return base.createConfigurationContext(config)
    }

    // The height in dp the lines and [chipRows] rows of chips take at [fontScale], [widthDp] wide.
    private fun heightDp(fontScale: Float, chipRows: Int, widthDp: Float = 300f): Float {
        val context = context(fontScale)
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.widget_connect, null) as ViewGroup
        root.findViewById<TextView>(R.id.widget_profile).text = "🇸🇪 Stockholm 001"
        root.findViewById<TextView>(R.id.widget_state).setText(R.string.service_connected)
        root.findViewById<TextView>(R.id.widget_group).text = " · Mullvad"
        root.findViewById<View>(R.id.widget_speed).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.widget_tx).text = "▲ 1.99 MB/s"
        root.findViewById<TextView>(R.id.widget_rx).text = "▼ 1.99 MB/s"
        for (rowId in listOf(R.id.widget_chips_1, R.id.widget_chips_2).take(chipRows)) {
            val row = root.findViewById<ViewGroup>(rowId)
            row.visibility = View.VISIBLE
            repeat(2) { inflater.inflate(R.layout.widget_chip, row, true) }
        }
        val density = context.resources.displayMetrics.density
        root.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return root.measuredHeight / density
    }

    // The state line's width at [fontScale] unconstrained, and as the layout gives it [widthDp] wide, in pixels.
    private fun stateWidths(fontScale: Float, locale: Locale, text: Int, widthDp: Float): Pair<Int, Int> {
        val context = context(fontScale, locale)
        val root = LayoutInflater.from(context).inflate(R.layout.widget_connect, null) as ViewGroup
        val state = root.findViewById<TextView>(R.id.widget_state)
        state.setText(text)
        root.findViewById<TextView>(R.id.widget_group).text = " · Mullvad"
        state.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val wanted = state.measuredWidth
        val density = context.resources.displayMetrics.density
        root.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return wanted to state.measuredWidth
    }

    @Test
    fun chipRowsAreOfferedOnlyWhereTheyFit_atFontScale13() {
        // The thresholds hold the layout at the largest font scale they allow for, with at most 2 dp to spare.
        for ((rows, threshold) in listOf(1 to WIDGET_CHIPS_MIN_HEIGHT, 2 to WIDGET_TWO_ROWS_MIN_HEIGHT)) {
            val needed = heightDp(1.3f, rows)
            assertTrue("$rows rows need $needed dp", needed <= threshold && threshold - needed <= 2f)
            // At the usual scale they fit with room.
            assertTrue(heightDp(1f, rows) <= threshold)
        }
    }

    @Test
    fun everyStateFitsBesideTheOrbAtTheNarrowestLines_atFontScale13() {
        for (locale in listOf(Locale.US, Locale("ru"))) {
            for (text in listOf(R.string.not_connected, R.string.connecting, R.string.stopping, R.string.service_connected)) {
                val (wanted, given) = stateWidths(1.3f, locale, text, WIDGET_LINES_MIN_WIDTH)
                assertTrue("$locale $text wants $wanted px, gets $given", given >= wanted)
            }
        }
    }
}
