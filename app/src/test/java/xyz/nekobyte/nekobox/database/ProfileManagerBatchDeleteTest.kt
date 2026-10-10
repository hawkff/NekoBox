package xyz.nekobyte.nekobox.database

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileManagerBatchDeleteTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun deleteProfiles_deletesBatchAndClearsSelectedProfile() = runTest {
        withContext(Dispatchers.IO) {
            val groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
            val profiles = (1..3).map { index ->
                ProxyEntity(groupId = groupId, userOrder = index.toLong()).apply {
                    putBean(
                        SOCKSBean().apply {
                            serverAddress = "192.0.2.$index"
                            serverPort = 1080 + index
                            name = "profile-$index"
                            initializeDefaultValues()
                        },
                    )
                    id = ProfileDatabase.proxyDao.addProxy(this)
                }
            }
            DataStore.selectedProxy = profiles[1].id

            ProfileManager.deleteProfiles(profiles.take(2))

            assertEquals(listOf(profiles[2].id), ProfileDatabase.proxyDao.getIdsByGroup(groupId))
            assertEquals(0L, DataStore.selectedProxy)
        }
    }
}
