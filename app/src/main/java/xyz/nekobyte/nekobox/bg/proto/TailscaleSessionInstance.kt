package xyz.nekobyte.nekobox.bg.proto

import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.buildConfig

/** The query and all its native consumers finish before runProbe releases the node leases. */
internal class TailscaleSessionInstance(profile: ProxyEntity) : BoxInstance(profile) {
    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    suspend fun runSession(query: suspend (TailscaleSessionInstance) -> Unit) = runProbe { query(this) }
}
