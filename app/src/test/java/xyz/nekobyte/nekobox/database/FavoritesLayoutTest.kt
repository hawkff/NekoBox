package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv.io
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

// Favorites keeps a layout of its own: the other tabs' layout never moves it while it holds a favorite.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FavoritesLayoutTest {
    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    @Test
    fun favoritesShowsTheTabsLayout_untilTheyChangeWhileItHoldsAFavorite_andKeepsItsOwnOnceSet() {
        // The pin of a deleted profile shows nothing to keep: Favorites comes with the layout the tabs have then.
        DataStore.updateFavoriteProfiles { it + 999_999L }
        io { DataStore.setTabsLayout(GroupLayout.TWO_COLUMNS) }
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.favoritesLayoutMode)
        // With a favorite there, the tabs change and Favorites keeps the layout it showed.
        val profile = io {
            val group = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Favorites layout"))
            ProfileDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = group).putBean(
                    SOCKSBean().applyDefaultValues().apply {
                        serverAddress = "192.0.2.1"
                        serverPort = 1080
                    },
                ),
            )
        }
        DataStore.updateFavoriteProfiles { it + profile }
        io { DataStore.setTabsLayout(GroupLayout.CARDS) }
        assertEquals(GroupLayout.CARDS, DataStore.groupLayoutMode)
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.favoritesLayoutMode)
        // Favorites' own layout leaves the tabs alone, and they leave it alone.
        DataStore.favoritesLayoutMode = GroupLayout.COMPACT
        assertEquals(GroupLayout.CARDS, DataStore.groupLayoutMode)
        io { DataStore.setTabsLayout(GroupLayout.TWO_COLUMNS) }
        assertEquals(GroupLayout.COMPACT, DataStore.favoritesLayoutMode)
        assertEquals(GroupLayout.TWO_COLUMNS, DataStore.groupLayoutMode)
    }
}
