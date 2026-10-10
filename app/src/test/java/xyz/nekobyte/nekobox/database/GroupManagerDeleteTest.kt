package xyz.nekobyte.nekobox.database

import android.app.Application
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupManagerDeleteTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun deleteGroup_singleSuccessCommitsBeforeSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (group, profile) = createGroupWithProfile(1L)
            DataStore.selectedProxy = profile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                GroupManager.deleteGroup(group.id) { reconfigurationCount++ }

                assertNull(ProfileDatabase.groupDao.getById(group.id))
                assertEquals(emptyList<Long>(), ProfileDatabase.proxyDao.getIdsByGroup(group.id))
                assertEquals(0L, DataStore.selectedProxy)
                assertEquals(listOf(group.id), listener.removedGroupIds)
                assertEquals(1, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_batchSuccessCommitsBeforeSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (firstGroup, firstProfile) = createGroupWithProfile(1L)
            val (secondGroup, secondProfile) = createGroupWithProfile(2L)
            val (untargetedGroup, untargetedProfile) = createGroupWithProfile(3L)
            DataStore.selectedProxy = secondProfile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                GroupManager.deleteGroup(listOf(firstGroup, secondGroup)) {
                    reconfigurationCount++
                }

                assertEquals(
                    setOf(untargetedGroup.id),
                    ProfileDatabase.groupDao.allGroups().map { it.id }.toSet(),
                )
                assertNull(ProfileDatabase.proxyDao.getById(firstProfile.id))
                assertNull(ProfileDatabase.proxyDao.getById(secondProfile.id))
                assertNotNull(ProfileDatabase.proxyDao.getById(untargetedProfile.id))
                assertEquals(0L, DataStore.selectedProxy)
                assertEquals(
                    listOf(firstGroup.id, secondGroup.id),
                    listener.removedGroupIds,
                )
                assertEquals(1, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_doesNotClearASelectionChangedAfterDeletionStarted() = runTest {
        withContext(Dispatchers.IO) {
            val (_, deletedProfile) = createGroupWithProfile(1L)
            val (_, replacementProfile) = createGroupWithProfile(2L)
            DataStore.selectedProxy = deletedProfile.id
            val selectedBeforeDelete = DataStore.selectedProxy
            DataStore.selectedProxy = replacementProfile.id

            GroupManager.clearDeletedSelection(selectedBeforeDelete, selectedWasDeleted = true)

            assertEquals(replacementProfile.id, DataStore.selectedProxy)
        }
    }

    @Test
    fun deleteGroup_singleRollbackRestoresRowsAndSuppressesSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (group, profile) = createGroupWithProfile(1L)
            DataStore.selectedProxy = profile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    assertDeletionFails {
                        GroupManager.deleteGroup(group.id) { reconfigurationCount++ }
                    }
                }

                assertNotNull(ProfileDatabase.groupDao.getById(group.id))
                assertEquals(listOf(profile.id), ProfileDatabase.proxyDao.getIdsByGroup(group.id))
                assertEquals(profile.id, DataStore.selectedProxy)
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
                assertEquals(0, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_batchRollbackRestoresRowsAndSuppressesSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (firstGroup, firstProfile) = createGroupWithProfile(1L)
            val (secondGroup, secondProfile) = createGroupWithProfile(2L)
            DataStore.selectedProxy = secondProfile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    assertDeletionFails {
                        GroupManager.deleteGroup(listOf(firstGroup, secondGroup)) {
                            reconfigurationCount++
                        }
                    }
                }

                assertEquals(
                    setOf(firstGroup.id, secondGroup.id),
                    ProfileDatabase.groupDao.allGroups().map { it.id }.toSet(),
                )
                assertEquals(
                    listOf(firstProfile.id),
                    ProfileDatabase.proxyDao.getIdsByGroup(firstGroup.id),
                )
                assertEquals(
                    listOf(secondProfile.id),
                    ProfileDatabase.proxyDao.getIdsByGroup(secondGroup.id),
                )
                assertEquals(secondProfile.id, DataStore.selectedProxy)
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
                assertEquals(0, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_publicWrappersSuppressSideEffectsOnRollback() = runTest {
        withContext(Dispatchers.IO) {
            val (singleGroup, singleProfile) = createGroupWithProfile(1L)
            val (batchGroup, batchProfile) = createGroupWithProfile(2L)
            val listener = RecordingListener()
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    DataStore.selectedProxy = singleProfile.id
                    assertDeletionFails { GroupManager.deleteGroup(singleGroup.id) }
                    assertEquals(singleProfile.id, DataStore.selectedProxy)

                    DataStore.selectedProxy = batchProfile.id
                    assertDeletionFails { GroupManager.deleteGroup(listOf(batchGroup)) }
                    assertEquals(batchProfile.id, DataStore.selectedProxy)
                }

                assertNotNull(ProfileDatabase.groupDao.getById(singleGroup.id))
                assertNotNull(ProfileDatabase.groupDao.getById(batchGroup.id))
                assertEquals(
                    listOf(singleProfile.id),
                    ProfileDatabase.proxyDao.getIdsByGroup(singleGroup.id),
                )
                assertEquals(
                    listOf(batchProfile.id),
                    ProfileDatabase.proxyDao.getIdsByGroup(batchGroup.id),
                )
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    private fun createGroupWithProfile(order: Long): Pair<ProxyGroup, ProxyEntity> {
        val group = ProxyGroup(userOrder = order).apply {
            id = ProfileDatabase.groupDao.createGroup(this)
        }
        val profile = ProxyEntity(groupId = group.id, userOrder = 1L).apply {
            id = ProfileDatabase.proxyDao.addProxy(this)
        }
        return group to profile
    }

    private suspend fun withFailingGroupDeleteTrigger(block: suspend () -> Unit) {
        val database = ProfileDatabase.instance.openHelper.writableDatabase
        database.execSQL(
            """
            CREATE TEMP TRIGGER fail_group_delete
            BEFORE DELETE ON proxy_groups
            BEGIN
                SELECT RAISE(ABORT, 'forced group deletion failure');
            END
            """.trimIndent(),
        )
        try {
            block()
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_group_delete")
        }
    }

    private suspend fun assertDeletionFails(block: suspend () -> Unit) {
        var failure: SQLiteException? = null
        try {
            block()
        } catch (exception: SQLiteException) {
            failure = exception
        }
        assertNotNull("Expected group deletion to fail", failure)
    }

    private class RecordingListener : GroupManager.Listener {
        val removedGroupIds = mutableListOf<Long>()

        override suspend fun groupAdd(group: ProxyGroup) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) = Unit

        override suspend fun groupRemoved(groupId: Long) {
            removedGroupIds += groupId
        }

        override suspend fun groupUpdated(groupId: Long) = Unit
    }
}
