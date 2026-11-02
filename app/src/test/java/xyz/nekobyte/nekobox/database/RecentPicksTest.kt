package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

// Each list keeps what was picked from it lately; turning Recent off forgets all of it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecentPicksTest {
    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    @Test
    fun aPickLeadsItsList_threeAtMost_eachListApart() {
        assertTrue(DataStore.recentsOn)
        for (id in listOf(1L, 2L, 3L, 4L)) DataStore.rememberPick(10L, id)
        DataStore.rememberPick(20L, 7L)
        // Picked again, it moves first instead of showing twice.
        DataStore.rememberPick(10L, 2L)
        assertEquals(mapOf(10L to listOf(2L, 4L, 3L), 20L to listOf(7L)), DataStore.recentPicks)
    }

    @Test
    fun turningRecentsOff_forgetsEveryPick_andKeepsNoneUntilOn() {
        DataStore.rememberPick(10L, 1L)
        DataStore.turnRecents(false)
        assertTrue(DataStore.recentPicks.isEmpty())
        DataStore.rememberPick(10L, 2L)
        assertTrue(DataStore.recentPicks.isEmpty())
        DataStore.turnRecents(true)
        DataStore.rememberPick(10L, 3L)
        assertEquals(mapOf(10L to listOf(3L)), DataStore.recentPicks)
    }

    @Test
    fun theFavoriteSet_followsThePins() {
        DataStore.updateFavoriteProfiles { it + 5L + 6L }
        assertEquals(setOf(5L, 6L), DataStore.favoriteSet)
        DataStore.updateFavoriteProfiles { it - 5L }
        assertEquals(setOf(6L), DataStore.favoriteSet)
    }
}
