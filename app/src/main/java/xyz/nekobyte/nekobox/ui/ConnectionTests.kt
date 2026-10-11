package xyz.nekobyte.nekobox.ui

import android.net.Network
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import xyz.nekobyte.nekobox.bg.runRequiredCompletion
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProfileTestResult
import xyz.nekobyte.nekobox.ktx.isIpAddress
import java.net.UnknownHostException

internal fun resolvePingAddress(address: String, network: Network?): String? {
    if (address.isIpAddress()) return address
    return try {
        network?.getAllByName(address)?.firstOrNull()?.hostAddress?.takeIf { it.isIpAddress() }
    } catch (_: UnknownHostException) {
        null
    }
}

internal suspend fun finishConnectionTests(job: Job, results: Collection<ProfileTestResult>) {
    try {
        job.cancelAndJoin()
        runRequiredCompletion(
            after = { results.map { it.profile.groupId }.distinct().forEach { GroupManager.postReload(it) } },
        ) {
            ProfileManager.updateTestResults(results.toList())
        }
    } finally {
        DataStore.runningTest = false
    }
}
