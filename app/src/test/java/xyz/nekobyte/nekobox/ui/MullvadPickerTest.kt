package xyz.nekobyte.nekobox.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.group.MullvadRelays

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MullvadPickerTest {
    // As the server list orders them: by country name, then city.
    private val relays = listOf(
        relay("nl", "ams", "Amsterdam", "001"),
        relay("us", "lax", "Los Angeles, CA", "001"),
        relay("us", "nyc", "New York, NY", "001"),
        relay("us", "nyc", "New York, NY", "002"),
    )

    private fun relay(country: String, city: String, name: String, number: String) = MullvadRelays.Relay(country, city, name, "$country-$city-wg-socks5-$number.relays.mullvad.net", 1080, "key")

    @Test
    fun rowsCountServers_andACountryWithOneCityHasNoCityRows() = withPicker(emptySet()) { dialog, _ ->
        val labels = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
        assertEquals(
            listOf("All countries · 4", "\uD83C\uDDF3\uD83C\uDDF1 Netherlands · 1", "\uD83C\uDDFA\uD83C\uDDF8 United States · 3", "\u2003Los Angeles, CA · 1", "\u2003New York, NY · 2"),
            labels,
        )
    }

    @Test
    fun checkingACountryChecksItsCities_andUncheckingACityLeavesTheRest() = withPicker(emptySet()) { dialog, picks ->
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.listView.performItemClick(null, 2, 2)
        assertEquals(listOf(false, false, true, true, true), checks(dialog))
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.listView.performItemClick(null, 4, 4)
        assertEquals(listOf(false, false, false, true, false), checks(dialog))
        pick(dialog)
        assertEquals(listOf(setOf("us-lax")), picks)
    }

    @Test
    fun aWholeCountryIsPickedByItsCode() = withPicker(emptySet()) { dialog, picks ->
        dialog.listView.performItemClick(null, 0, 0)
        assertEquals(listOf(true, true, true, true, true), checks(dialog))
        dialog.listView.performItemClick(null, 1, 1)
        assertEquals(listOf(false, false, true, true, true), checks(dialog))
        pick(dialog)
        assertEquals(listOf(setOf("us")), picks)
    }

    @Test
    fun aListHoldingEveryServerOpensChecked_andKeepsEveryServer() = withPicker(null) { dialog, picks ->
        assertEquals(listOf(true, true, true, true, true), checks(dialog))
        assertEquals(dialog.context.getString(android.R.string.ok), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        pick(dialog)
        assertEquals(1, picks.size)
        assertNull(picks[0])
    }

    @Test
    fun aSavedCountryWithoutServersNowStaysPicked() = withPicker(setOf("nl", "se")) { dialog, picks ->
        assertEquals(listOf(false, true, false, false, false), checks(dialog))
        pick(dialog)
        assertEquals(listOf(setOf("nl", "se")), picks)
    }

    private fun checks(dialog: AlertDialog) = (0 until dialog.listView.adapter.count).map { dialog.listView.isItemChecked(it) }

    private fun pick(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun withPicker(saved: Set<String>?, action: (AlertDialog, MutableList<Set<String>?>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context: Context = ContextThemeWrapper(controller.get(), R.style.Theme_NekoBox)
            val picks = ArrayList<Set<String>?>()
            val dialog = showMullvadPicker(context, relays, saved, adding = saved?.isEmpty() == true) { picks += it }
            try {
                action(dialog, picks)
            } finally {
                dialog.dismiss()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
