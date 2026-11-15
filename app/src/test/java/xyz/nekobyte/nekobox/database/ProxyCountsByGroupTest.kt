package xyz.nekobyte.nekobox.database

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProxyCountsByGroupTest {

    @Test
    fun countsTheProfilesOfEveryGroupThatHasAny() {
        ConfigBuilderTestEnv.reset()
        ConfigBuilderTestEnv.io {
            val (two, one, _) = (1L..3L).map { ProfileDatabase.groupDao.createGroup(ProxyGroup(userOrder = it)) }
            repeat(2) { ProfileDatabase.proxyDao.addProxy(ProxyEntity(groupId = two, userOrder = it + 1L)) }
            ProfileDatabase.proxyDao.addProxy(ProxyEntity(groupId = one, userOrder = 1L))

            // The empty third group has no entry.
            assertEquals(mapOf(two to 2L, one to 1L), ProfileDatabase.proxyDao.countsByGroup())
        }
    }
}
