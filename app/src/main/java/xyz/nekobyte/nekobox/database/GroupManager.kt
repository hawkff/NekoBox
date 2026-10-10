package xyz.nekobyte.nekobox.database

import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.bg.SubscriptionUpdater
import xyz.nekobyte.nekobox.fmt.tailscale.pruneTailscaleState
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
        suspend fun groupProgressUpdated(groupId: Long) = Unit
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean,
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected != 0L && ProfileDatabase.proxyDao.getById(selected)?.groupId == groupId) {
            DataStore.selectedProxy = 0L
        }
        ProfileDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = ProfileDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        ProfileDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(ProfileDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postProgress(groupId: Long) {
        iterator { groupProgressUpdated(groupId) }
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = ProfileDatabase.groupDao.nextOrder() ?: 1
        group.id = ProfileDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        ProfileDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) = deleteGroup(groupId) { SubscriptionUpdater.reconfigureUpdater() }

    internal suspend fun deleteGroup(groupId: Long, reconfigureUpdater: suspend () -> Unit) {
        val selected = DataStore.selectedProxy
        val clearSelected =
            selected != 0L && ProfileDatabase.proxyDao.getById(selected)?.groupId == groupId
        ProfileDatabase.instance.runInTransaction {
            ProfileDatabase.proxyDao.deleteByGroup(groupId)
            ProfileDatabase.groupDao.deleteById(groupId)
        }
        clearDeletedSelection(selected, clearSelected)
        RoutingProfiles.deleteBySource(RoutingProfiles.subscriptionSource(groupId))
        pruneTailscaleState()
        iterator { groupRemoved(groupId) }
        reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) = deleteGroup(group) { SubscriptionUpdater.reconfigureUpdater() }

    internal suspend fun deleteGroup(group: List<ProxyGroup>, reconfigureUpdater: suspend () -> Unit) {
        val ids = group.map { it.id }.toSet()
        val selected = DataStore.selectedProxy
        val clearSelected =
            selected != 0L && ProfileDatabase.proxyDao.getById(selected)?.groupId in ids
        ProfileDatabase.instance.runInTransaction {
            ProfileDatabase.proxyDao.deleteByGroup(ids.toLongArray())
            ProfileDatabase.groupDao.deleteGroup(group)
        }
        clearDeletedSelection(selected, clearSelected)
        for (id in ids) RoutingProfiles.deleteBySource(RoutingProfiles.subscriptionSource(id))
        pruneTailscaleState()
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        reconfigureUpdater()
    }

    internal fun clearDeletedSelection(selectedBeforeDelete: Long, selectedWasDeleted: Boolean) {
        if (selectedWasDeleted && DataStore.selectedProxy == selectedBeforeDelete) {
            DataStore.selectedProxy = 0L
        }
    }
}
