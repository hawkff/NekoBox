package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import xyz.nekobyte.nekobox.*
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.NekoBoxConnection
import xyz.nekobyte.nekobox.bg.NetworkAutomation
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
import xyz.nekobyte.nekobox.fmt.tuic.TuicBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.isTLS
import xyz.nekobyte.nekobox.ktx.*
import xyz.nekobyte.nekobox.proxy.anytls.AnyTLSBean
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
        val lookups = Semaphore(5)
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
                        // One lookup a thread: a lookup that waits for the service's answer gives its thread up.
                        lookups.acquire()
                        try {
                            val results = if (
                                NekoBox.underlyingNetwork != null &&
                                DataStore.enableFakeDns &&
                                DataStore.serviceMode == Key.MODE_VPN &&
                                // Asked of Android for every lookup: the VPN can come up while a long update is at
                                // work, and of the service this process knows only what its last screen saw.
                                NetworkAutomation.ownVpnCarriesThisApp()
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
                        } finally {
                            lookups.release()
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

    internal fun rewriteAddress(bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean) {
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

                is TuicBean -> {
                    if (sni!!.isBlank()) sni = bean.serverAddress
                }

                is AnyTLSBean -> {
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

                var declined = false
                val done = try {
                    // An update by hand over plain HTTP, or of a subscription kept for when a proxy is connected,
                    // asks first when none is. Whether one is, is asked of the service: the local API also starts
                    // updates with no screen bound to it, and this process then knows only what its last screen
                    // saw. An update cancelled while it asks gives the group up like any other.
                    declined = byUser &&
                        (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly!!) &&
                        NekoBoxConnection.ask(app) { BaseService.State.values()[it.state].takeIf { state -> state.connected } } == null &&
                        (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning)))
                    if (!declined) {
                        RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                        SubscriptionUpdater.notifyExpiry(proxyGroup)
                    }
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
                // Out here it runs once: in there, a failure to tell the lists would have it run again.
                if (declined) finishUpdate(proxyGroup)
                done
            }
        }

        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }
    }
}
