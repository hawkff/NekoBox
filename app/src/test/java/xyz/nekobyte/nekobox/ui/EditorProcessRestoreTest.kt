package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.ui.profile.SocksSettingsActivity

/** An editor rebuilt in a new process, whose in-memory draft is gone: a saved state without this process's store id. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorProcessRestoreTest {

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        // One process here; Robolectric cannot connect Room's remote invalidation service.
        shadowOf(application).declareComponentUnbindable(ComponentName(application, "androidx.room.MultiInstanceInvalidationService"))
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun groupEditorStartsOverFromTheStoredGroup() {
        val id = ConfigBuilderTestEnv.io {
            ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Work", type = GroupType.SUBSCRIPTION, userOrder = 1L))
        }
        DataStore.editingId = 0L
        DataStore.groupName = ""
        DataStore.groupType = GroupType.BASIC
        val intent = Intent(RuntimeEnvironment.getApplication(), GroupSettingsActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)
        val controller = Robolectric.buildActivity(GroupSettingsActivity::class.java, intent).create(Bundle())
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (DataStore.editingId != id || DataStore.groupName != "Work") {
                check(System.currentTimeMillis() < deadline) { "the editor never read the stored group" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(GroupType.SUBSCRIPTION, DataStore.groupType)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun profileEditorCloses() {
        val controller = Robolectric.buildActivity(SocksSettingsActivity::class.java).create(Bundle())
        try {
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }
}
