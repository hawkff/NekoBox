package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        root.findViewById<TextView>(R.id.widget_speed).apply {
            visibility = View.VISIBLE
            text = widgetSpeedLine("1.99 MB/s", "1.99 MB/s")
        }
        for (rowId in listOf(R.id.widget_chips_1, R.id.widget_chips_2).take(chipRows)) {
            val row = root.findViewById<ViewGroup>(rowId)
            row.visibility = View.VISIBLE
            repeat(2) { inflater.inflate(R.layout.widget_chip, row, true) }
        }
        val density = context.resources.displayMetrics.density
        root.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return root.measuredHeight / density
    }

    // The lines beside the orb, laid out [widthDp] wide at [fontScale] in [locale], showing [state] and the speed.
    private fun lines(fontScale: Float, locale: Locale, state: Int, widthDp: Float): ViewGroup {
        val context = context(fontScale, locale)
        val root = LayoutInflater.from(context).inflate(R.layout.widget_connect, null) as ViewGroup
        root.findViewById<TextView>(R.id.widget_profile).text = "🇸🇪 Stockholm 001"
        root.findViewById<TextView>(R.id.widget_state).setText(state)
        root.findViewById<TextView>(R.id.widget_group).text = " · Mullvad"
        root.findViewById<TextView>(R.id.widget_speed).apply {
            visibility = View.VISIBLE
            text = widgetSpeedLine("1.99 MB/s", "1.99 MB/s")
        }
        val density = context.resources.displayMetrics.density
        root.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    // Shown and not cut off mid-glyph: it fits, or it ends in "…" with some of its text before it.
    private fun assertShownWhole(what: String, view: TextView, ellipsisAllowed: Boolean) {
        val layout = view.layout
        assertTrue("$what has no room", view.width > 0 && layout != null)
        val ellipsized = layout.getEllipsisCount(0) > 0
        val room = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        assertTrue("$what is clipped", ellipsized || layout.getLineWidth(0) <= room + 0.5f)
        assertTrue("$what shows nothing", view.text.length - layout.getEllipsisCount(0) >= 1)
        if (!ellipsisAllowed) assertFalse("$what ends in an ellipsis", ellipsized)
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
    fun aThreeCellWidgetOfAFiveColumnHomeScreenKeepsItsLines() {
        // From the narrowest lines (180 dp) to a three-cell widget of a five-column home screen (about 203 dp) and on,
        // in English and Russian: the profile, the state and the speed show, ending in "…" where they do not fit, never
        // cut off. At the usual font scale every state shows whole.
        assertEquals(180f, WIDGET_LINES_MIN_WIDTH)
        for (width in listOf(WIDGET_LINES_MIN_WIDTH, 190f, 203f, 206f)) {
            for (scale in listOf(1f, 1.3f)) {
                for (locale in listOf(Locale.US, Locale("ru"))) {
                    for (state in listOf(R.string.not_connected, R.string.connecting, R.string.stopping, R.string.service_connected)) {
                        val root = lines(scale, locale, state, width)
                        val where = "$width dp, $scale, $locale, ${root.findViewById<TextView>(R.id.widget_state).text}"
                        assertShownWhole("profile at $where", root.findViewById(R.id.widget_profile), ellipsisAllowed = true)
                        assertShownWhole("state at $where", root.findViewById(R.id.widget_state), ellipsisAllowed = scale > 1f)
                        assertShownWhole("speed at $where", root.findViewById(R.id.widget_speed), ellipsisAllowed = true)
                    }
                }
            }
        }
    }
}
