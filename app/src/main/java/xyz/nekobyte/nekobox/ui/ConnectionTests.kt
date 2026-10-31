package xyz.nekobyte.nekobox.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import xyz.nekobyte.nekobox.bg.proto.TestInstance
import xyz.nekobyte.nekobox.bg.runRequiredCompletion
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProfileTestResult
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.isIpAddress

/**
 * The address a ping goes to: the server's own, or what its name stands for through a test core
 * with the profile's resolver (Direct DNS, or its subscription's), as its connection would look it
 * up. The system resolver would send the name to the network's DNS in the clear. Null when it does
 * not resolve.
 */
internal suspend fun resolvePingAddress(
    profile: ProxyEntity,
    resolve: suspend (ProxyEntity) -> String? = { TestInstance(it, "", DataStore.connectionTestTimeout).resolveServer() },
): String? {
    val server = profile.requireBean().serverAddress?.takeIf { it.isNotBlank() } ?: return null
    if (server.isIpAddress()) return server
    return try {
        resolve(profile)?.takeIf { it.isIpAddress() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logs.w(e)
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
