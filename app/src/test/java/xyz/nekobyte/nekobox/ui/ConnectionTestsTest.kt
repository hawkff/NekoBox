package xyz.nekobyte.nekobox.ui

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileTestResult
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectionTestsTest {
    @Before fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun offlineHostnamesFailWithoutThrowingWhileIpLiteralsRemainUsable() {
        assertNull(resolvePingAddress("proxy.example", null))
        assertEquals("192.0.2.1", resolvePingAddress("192.0.2.1", null))
        assertEquals("2001:db8::1", resolvePingAddress("2001:db8::1", null))
    }

    @Test
    fun cleanupWaitsForCancelledWorkersBeforeAllowingAnotherBatch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        started.await()
        DataStore.runningTest = true
        val finish = async(Dispatchers.IO) { finishConnectionTests(worker, emptyList()) }
        try {
            while (!worker.isCancelled) yield()
            assertTrue(DataStore.runningTest)
        } finally {
            release.complete(Unit)
            finish.await()
        }
        assertFalse(DataStore.runningTest)
    }

    @Test
    fun persistenceFailureStillReleasesTheBatchFlag() = ConfigBuilderTestEnv.io {
        val profile = ProxyEntity(groupId = 1).putBean(SOCKSBean().apply { initializeDefaultValues() })
        profile.id = ProfileDatabase.proxyDao.addProxy(profile)
        val result = ProfileTestResult(profile)
        ProfileDatabase.proxyDao.updateProxyRow(profile.copy(socksBean = null))
        DataStore.runningTest = true
        val worker = Job().apply { complete() }
        assertNotNull(runCatching { runBlocking { finishConnectionTests(worker, listOf(result)) } }.exceptionOrNull())
        assertFalse(DataStore.runningTest)
    }
}
