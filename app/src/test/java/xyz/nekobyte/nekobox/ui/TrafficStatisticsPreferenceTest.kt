package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrafficStatisticsPreferenceTest {
    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        DataStore.speedInterval = 0
        DataStore.serviceState = BaseService.State.Stopped
    }

    @Test
    fun statisticsRemainEditableWhenNotificationSpeedsAreOffOrChanged() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_NekoBox) }
        val fragment = SettingsPreferenceFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, "screen_notification") }
        }
        try {
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            val statistics = fragment.findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
            val interval = fragment.findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
            assertTrue(statistics.isEnabled)
            assertNotNull(statistics.onPreferenceChangeListener)
            assertSame(interval.onPreferenceChangeListener, statistics.onPreferenceChangeListener)
            for (value in listOf("1000", "0")) {
                assertTrue(interval.callChangeListener(value))
                interval.value = value
                assertTrue(statistics.isEnabled)
                val before = statistics.isChecked
                statistics.performClick()
                assertEquals(!before, statistics.isChecked)
                assertEquals(statistics.isChecked, DataStore.profileTrafficStatistics)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
