package xyz.nekobyte.nekobox.database

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileTestResultsTest {
    @Before
    fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun resultPreservesNewTrafficAndOrdering() = ConfigBuilderTestEnv.io {
        val result = result()
        val latest = ProfileDatabase.proxyDao.getById(result.profile.id)!!.apply {
            rx = 200
            tx = 300
            lifetimeRx = 400
            lifetimeTx = 500
            userOrder = 9
        }
        ProfileDatabase.proxyDao.updateProxy(latest)
        runBlocking { ProfileManager.updateTestResults(listOf(result)) }
        val saved = ProfileDatabase.proxyDao.getById(result.profile.id)!!
        assertEquals(latest.copy(status = 1, ping = 42, error = null), saved)
    }

    @Test
    fun changedConfigurationIdentityOrGroupDiscardsResult() = ConfigBuilderTestEnv.io {
        for (change in listOf<(ProxyEntity) -> Unit>(
            { it.socksBean!!.password = "replacement" },
            { it.uuid = "replacement-identity" },
            { it.groupId = 2 },
        )) {
            val result = result()
            val latest = ProfileDatabase.proxyDao.getById(result.profile.id)!!.also(change)
            ProfileDatabase.proxyDao.updateProxy(latest)
            runBlocking { ProfileManager.updateTestResults(listOf(result)) }
            assertEquals(latest, ProfileDatabase.proxyDao.getById(result.profile.id))
        }
    }

    @Test
    fun runtimeBeanMutationDoesNotChangeTheComparedConfiguration() = ConfigBuilderTestEnv.io {
        val result = result()
        result.profile.socksBean!!.serverAddress = "127.0.0.1"
        runBlocking { ProfileManager.updateTestResults(listOf(result)) }
        val saved = ProfileDatabase.proxyDao.getById(result.profile.id)!!
        assertEquals(42, saved.ping)
        assertEquals("192.0.2.1", saved.socksBean!!.serverAddress)
    }

    @Test
    fun deletedProfileIsNotRecreated() = ConfigBuilderTestEnv.io {
        val result = result()
        ProfileDatabase.proxyDao.deleteById(result.profile.id)
        runBlocking { ProfileManager.updateTestResults(listOf(result)) }
        assertNull(ProfileDatabase.proxyDao.getById(result.profile.id))
    }

    private fun result(): ProfileTestResult {
        val profile = ProxyEntity(groupId = 1, userOrder = 1, status = 3, error = "old failure").putBean(
            SOCKSBean().apply {
                serverAddress = "192.0.2.1"
                serverPort = 1080
                password = "original"
                initializeDefaultValues()
            },
        ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        return ProfileTestResult(profile).also {
            profile.status = 1
            profile.ping = 42
            profile.error = null
        }
    }
}
