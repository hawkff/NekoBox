package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.*
import xyz.nekobyte.nekobox.*
import xyz.nekobyte.nekobox.bg.SubscriptionUpdater
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.isTLS
import xyz.nekobyte.nekobox.ktx.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
    )

    data class Progress(
        var max: Int,
    ) {
        private val counter = AtomicInteger()
        val progress get() = counter.get()
        fun increment() = counter.incrementAndGet()
    }

    protected suspend fun forceResolve(profiles: List<AbstractBean>, groupId: Long?) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupPool = newFixedThreadPoolContext(5, "DNS Lookup")
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postProgress(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER

        try {
            coroutineScope {
                for (profile in profiles) {
                    when (profile) {
                        // SNI rewrite unsupported
                        is NaiveBean -> continue
                    }

                    if (profile.serverAddress!!.isIpAddress()) continue

                    launch(lookupPool) {
                        try {
                            val results = if (
                                NekoBox.underlyingNetwork != null &&
                                DataStore.enableFakeDns &&
                                DataStore.serviceState.started &&
                                DataStore.serviceMode == Key.MODE_VPN
                            ) {
                                // FakeDNS
                                NekoBox.underlyingNetwork!!
                                    .getAllByName(profile.serverAddress)
                                    .filterNotNull()
                            } else {
                                // System DNS is enough (when VPN connected, it uses v2ray-core)
                                InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                            }
                            if (results.isEmpty()) error("empty response")
                            rewriteAddress(profile, results, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
                        }
                        if (groupId != null) {
                            progress.increment()
                            GroupManager.postProgress(groupId)
                        }
                    }
                }
            } // coroutineScope joins all children here
        } finally {
            lookupPool.close()
        }
    }

    protected fun rewriteAddress(bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni!!.isBlank()) sni = bean.serverAddress
                }

                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni!!.isBlank()) sni = bean.serverAddress
                    }
                }

                is TrojanBean -> {
                    if (sni!!.isBlank()) sni = bean.serverAddress
                }

                is HysteriaBean -> {
                    if (sni!!.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(
            proxyGroup: ProxyGroup,
            byUser: Boolean,
            userInterface: GroupManager.Interface? = GroupManager.userInterface,
        ): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) {
                    // already updating this group in another run; skip quietly
                    return@coroutineScope false
                }
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription!!
                val connected = DataStore.serviceState.connected

                if (byUser && (
                        subscription.link?.startsWith(
                            "http://",
                        ) == true || subscription.updateWhenConnectedOnly!!
                        ) && !connected
                ) {
                    if (userInterface == null || !userInterface.confirm(
                            app.getString(R.string.update_subscription_warning),
                        )
                    ) {
                        finishUpdate(proxyGroup)
                        return@coroutineScope true
                    }
                }

                try {
                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    SubscriptionUpdater.notifyExpiry(proxyGroup)
                    true
                } catch (e: CancellationException) {
                    finishUpdate(proxyGroup)
                    throw e
                } catch (e: Throwable) {
                    Logs.w(e)
                    userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }

        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }
    }
}
