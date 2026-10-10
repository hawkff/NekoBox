package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.tailscale.TailscaleBean
import xyz.nekobyte.nekobox.fmt.tailscale.acquireTailscaleState
import xyz.nekobyte.nekobox.fmt.tailscale.profilesForBackup
import xyz.nekobyte.nekobox.fmt.tailscale.pruneTailscaleState
import xyz.nekobyte.nekobox.fmt.tailscale.resetTailscaleIdentity
import xyz.nekobyte.nekobox.fmt.tailscale.tailscaleStateFile
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleStateTest {

    @Test
    fun pruneKeepsLiveNodesAndRemovesOrphans() {
        ConfigBuilderTestEnv.reset()
        // Shared static state; another test may have left the service marked as running.
        DataStore.serviceState = BaseService.State.Idle
        val node = ProxyEntity(groupId = 1L).putBean(TailscaleBean().apply { initializeDefaultValues() })
        node.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(node) }
        val live = tailscaleStateFile(node.id).apply {
            mkdirs()
            resolve("tailscaled.state").writeText("x")
        }
        val orphan = tailscaleStateFile(node.id + 100).apply {
            mkdirs()
            resolve("tailscaled.state").writeText("x")
        }
        val stray = live.parentFile!!.resolve("not-an-id").apply { mkdirs() }

        ConfigBuilderTestEnv.io { pruneTailscaleState() }

        assertTrue(live.resolve("tailscaled.state").isFile)
        assertFalse(orphan.exists())
        assertFalse(stray.exists())

        // A restore passes the ids whose profile content survived; everything else goes.
        val replaced = tailscaleStateFile(node.id + 1).apply { mkdirs() }
        ConfigBuilderTestEnv.io { pruneTailscaleState(keep = setOf(node.id)) }
        assertTrue(live.exists())
        assertFalse(replaced.exists())

        // Nothing is pruned while the service runs: the node may still be writing its state.
        ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.deleteById(node.id) }
        DataStore.serviceState = BaseService.State.Connected
        ConfigBuilderTestEnv.io { pruneTailscaleState() }
        assertTrue(live.exists())
        DataStore.serviceState = BaseService.State.Stopping
        ConfigBuilderTestEnv.io { pruneTailscaleState() }
        assertTrue(live.exists())
        assertFalse(BaseService.State.Stopping.started)
        DataStore.serviceState = BaseService.State.Idle
        ConfigBuilderTestEnv.io { pruneTailscaleState() }
        assertFalse(live.exists())
    }

    @Test
    fun pruneRetainsNodeWithLockIoFailureAndContinuesToNextCandidate() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        val blockedId = 91_001L
        val removableId = 91_002L
        val blocked = tailscaleStateFile(blockedId).apply { mkdirs() }
        val removable = tailscaleStateFile(removableId).apply { mkdirs() }
        blocked.resolve("tailscaled.state").writeText("retained-state")
        removable.resolve("tailscaled.state").writeText("orphan-state")
        val badLock = File(NekoBox.application.noBackupFilesDir, "tailscale-locks/$blockedId.lock")
        assertTrue(badLock.mkdirs())
        try {
            val failure = runCatching { acquireTailscaleState(listOf(blockedId)).close() }.exceptionOrNull()
            assertTrue(failure is IOException)
            ConfigBuilderTestEnv.io {
                pruneTailscaleState(keep = emptySet(), profileIds = listOf(blockedId, removableId))
            }
            assertEquals("retained-state", blocked.resolve("tailscaled.state").readText())
            assertFalse(removable.exists())
            assertTrue(badLock.delete())
            ConfigBuilderTestEnv.io {
                pruneTailscaleState(keep = emptySet(), profileIds = listOf(blockedId))
            }
            assertFalse(blocked.exists())
        } finally {
            badLock.delete()
            blocked.deleteRecursively()
            removable.deleteRecursively()
        }
    }

    @Test
    fun probeLeaseRefusesResetAndPruningEvenWithCachedIdle() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        val node = node()
        val directory = tailscaleStateFile(node.id).apply { mkdirs() }
        acquireTailscaleState(listOf(node.id)).use {
            assertNotNull(ConfigBuilderTestEnv.io { runCatching { resetTailscaleIdentity(node.id) }.exceptionOrNull() })
            ConfigBuilderTestEnv.io { pruneTailscaleState(emptySet()) }
            assertTrue(directory.exists())
            assertEquals(node.uuid, ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getById(node.id)!!.uuid })
        }
        ConfigBuilderTestEnv.io { pruneTailscaleState(emptySet()) }
        assertFalse(directory.exists())
    }

    @Test
    fun resetRotatesMarkerAndStaleUpdatesCannotUndoIt() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        val node = node()
        val original = node.uuid
        val directory = tailscaleStateFile(node.id).apply { mkdirs() }
        DataStore.serviceState = BaseService.State.Stopping
        assertNotNull(ConfigBuilderTestEnv.io { runCatching { resetTailscaleIdentity(node.id) }.exceptionOrNull() })
        assertTrue(directory.exists())
        DataStore.serviceState = BaseService.State.Idle
        val marker = ConfigBuilderTestEnv.io { resetTailscaleIdentity(node.id) }
        assertNotEquals(original, marker)
        assertFalse(directory.exists())
        ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.updateProxy(node) }
        assertEquals(marker, ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getById(node.id)!!.uuid })
    }

    @Test
    fun exportDurablyMarksLegacyNodeWithoutDeletingCredentialsAndClonesGetFreshMarker() {
        ConfigBuilderTestEnv.reset()
        val node = node()
        ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.setTailscaleMarker(node.id, "") }
        val state = tailscaleStateFile(node.id).apply { mkdirs() }.resolve("tailscaled.state")
        state.writeText("local-state")
        DataStore.serviceState = BaseService.State.Connected
        try {
            acquireTailscaleState(listOf(node.id)).use {
                val failure = ConfigBuilderTestEnv.io { runCatching { profilesForBackup() }.exceptionOrNull() }
                assertTrue(failure is IllegalStateException)
                assertTrue(failure!!.message!!.contains("Stop the service or close the active probe and retry"))
                assertEquals("", ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getById(node.id)!!.uuid })
                assertEquals("local-state", state.readText())
            }
            val exported = ConfigBuilderTestEnv.io { profilesForBackup().single() }
            assertTrue(exported.uuid.isNotBlank())
            acquireTailscaleState(listOf(node.id)).use {
                assertEquals(exported.uuid, ConfigBuilderTestEnv.io { profilesForBackup().single().uuid })
            }
            assertEquals("local-state", state.readText())
            val clone = exported.copy(id = 0)
            ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(clone) }
            assertNotEquals(exported.uuid, clone.uuid)
        } finally {
            DataStore.serviceState = BaseService.State.Idle
        }
    }

    @Test
    fun busyLegacyNodePreventsPartialBackupMarkerUpgrades() {
        ConfigBuilderTestEnv.reset()
        val first = node()
        val second = node()
        ConfigBuilderTestEnv.io {
            ProfileDatabase.proxyDao.setTailscaleMarker(first.id, "")
            ProfileDatabase.proxyDao.setTailscaleMarker(second.id, "")
        }
        acquireTailscaleState(listOf(second.id)).use {
            assertNotNull(ConfigBuilderTestEnv.io { runCatching { profilesForBackup() }.exceptionOrNull() })
            assertEquals(listOf("", ""), ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getAll().map { it.uuid } })
            acquireTailscaleState(listOf(first.id)).close()
        }
        val exported = ConfigBuilderTestEnv.io { profilesForBackup() }
        assertTrue(exported.all { it.uuid.isNotBlank() })
        assertEquals(exported.map { it.uuid }, ConfigBuilderTestEnv.io { profilesForBackup().map { it.uuid } })
    }

    private fun node() = ProxyEntity(groupId = 1L).putBean(TailscaleBean().apply { initializeDefaultValues() }).also {
        it.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(it) }
    }
}
