package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

// Favorites keeps a layout of its own: the other tabs' layout never moves it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FavoritesLayoutTest {
    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    @Test
    fun favoritesShowsTheTabsLayout_untilTheyChange_andKeepsItsOwnOnceSet() {
        // Without a favorite, Favorites shows nothing to keep: it comes with the layout the tabs have then.
        DataStore.setTabsLayout(GroupLayout.TWO_COLUMNS, favoritesShown = false)
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.favoritesLayoutMode)
        // The tabs change while Favorites shows: it keeps the layout it showed.
        DataStore.setTabsLayout(GroupLayout.CARDS, favoritesShown = true)
        assertEquals(GroupLayout.CARDS, DataStore.groupLayoutMode)
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.favoritesLayoutMode)
        // Favorites' own layout leaves the tabs alone, and they leave it alone.
        DataStore.favoritesLayoutMode = GroupLayout.COMPACT
        assertEquals(GroupLayout.CARDS, DataStore.groupLayoutMode)
        DataStore.setTabsLayout(GroupLayout.TWO_COLUMNS, favoritesShown = true)
        assertEquals(GroupLayout.COMPACT, DataStore.favoritesLayoutMode)
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.groupLayoutMode)
    }
}
