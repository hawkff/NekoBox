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
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileTestResult
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
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
    fun cleanupWaitsForCanceledWorkersBeforeAllowingAnotherBatch() = runBlocking {
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
    fun persistenceFailureStillReloadsDistinctGroupsAndReleasesTheBatchFlag() = ConfigBuilderTestEnv.io {
        val results = listOf(result(1), result(2), result(1))
        val broken = results[1].profile
        ProfileDatabase.proxyDao.updateProxyRow(broken.copy(socksBean = null))
        val reloaded = mutableListOf<Long>()
        withReloadListener({ reloaded.add(it) }) {
            DataStore.runningTest = true
            val worker = Job().apply { complete() }
            val failure = runCatching { runBlocking { finishConnectionTests(worker, results) } }.exceptionOrNull()
            assertEquals(NullPointerException::class.java, failure?.javaClass)
            assertEquals(listOf(1L, 2L), reloaded)
            assertFalse(DataStore.runningTest)
            assertEquals(0, ProfileDatabase.proxyDao.getById(results.first().profile.id)!!.ping)
        }
    }

    @Test
    fun reloadFailureDoesNotReplacePersistenceFailure() = ConfigBuilderTestEnv.io {
        val result = result(1)
        ProfileDatabase.proxyDao.updateProxyRow(result.profile.copy(socksBean = null))
        val reloadFailure = IllegalArgumentException("reload failed")
        withReloadListener({ throw reloadFailure }) {
            DataStore.runningTest = true
            val worker = Job().apply { complete() }
            val failure = runCatching { runBlocking { finishConnectionTests(worker, listOf(result)) } }.exceptionOrNull()
            assertEquals(NullPointerException::class.java, failure?.javaClass)
            assertSame(reloadFailure, failure!!.suppressed.single())
            assertFalse(DataStore.runningTest)
        }
    }

    @Test
    fun reloadFailureOrCancellationPropagatesAfterSuccessfulPersistence() = ConfigBuilderTestEnv.io {
        for (reloadFailure in listOf(IllegalArgumentException("reload failed"), CancellationException("canceled"))) {
            val result = result(1)
            withReloadListener({ throw reloadFailure }) {
                DataStore.runningTest = true
                val worker = Job().apply { complete() }
                val failure = runCatching { runBlocking { finishConnectionTests(worker, listOf(result)) } }.exceptionOrNull()
                assertSame(reloadFailure, failure)
                assertFalse(DataStore.runningTest)
                assertEquals(42, ProfileDatabase.proxyDao.getById(result.profile.id)!!.ping)
            }
        }
    }

    private fun result(groupId: Long): ProfileTestResult {
        val profile = ProxyEntity(groupId = groupId).putBean(SOCKSBean().apply { initializeDefaultValues() })
        profile.id = ProfileDatabase.proxyDao.addProxy(profile)
        return ProfileTestResult(profile).also {
            profile.status = 1
            profile.ping = 42
        }
    }

    private fun withReloadListener(onReload: (Long) -> Unit, block: () -> Unit) {
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = onReload(groupId)
        }
        GroupManager.addListener(listener)
        try {
            block()
        } finally {
            GroupManager.removeListener(listener)
        }
    }
}
