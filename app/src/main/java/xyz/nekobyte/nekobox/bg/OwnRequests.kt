package xyz.nekobyte.nekobox.bg

import kotlinx.coroutines.runBlocking
import libcore.HTTPClient
import okhttp3.Credentials
import okhttp3.OkHttpClient
import xyz.nekobyte.nekobox.aidl.LocalProxy
import xyz.nekobyte.nekobox.ktx.app
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Where the app's own requests go through the running proxy, or null to connect directly: no service is connected, or
 * it runs a config without a local port these requests can use, as VPN mode does unless a setting asks for it. The
 * service is asked, because the screens' process does not otherwise know what it runs. The port and the login are
 * those of the config it runs, not those of the settings, which may have changed since: whatever else listens on
 * the port the settings name gets neither the requests nor the login. Blocks until the service answers; not for the
 * main thread, where the answer arrives.
 */
fun localProxy(): LocalProxy? = runBlocking { NekoBoxConnection.ask(app) { it.localProxy } }

/**
 * Sends this client's requests through the running proxy when there is one to go through: see [localProxy]. The
 * client keeps the port and the login for all it does afterwards. For every connection it makes there it asks,
 * before it sends a byte, whether the start it was set up in still runs with the port open: the port has then been
 * the service's all along, and so is what the connection reached. Once that start is gone the client's requests
 * fail. They go neither to whatever holds the port by then nor around the proxy.
 */
fun HTTPClient.throughRunningProxy() {
    val local = localProxy() ?: return
    // A failure to ask is no yes. Nothing may be thrown at the core, which asks.
    trySocks5(local.port, local.user, local.password) { runCatching { localProxy()?.start == local.start }.getOrDefault(false) }
}

/**
 * Sends this client's requests through [local], the local port the running proxy reported, as an HTTP proxy. The
 * login is given only while the service still reports that port with that login, which is while the start this
 * client was set up in still runs: the port has then been the service's ever since, and so is whatever a connection
 * of this client reached. A client kept across a stop or a restart is given no login, of the old start or the new
 * one: a connection it made in between can have reached something else, which could ask for the login later.
 */
fun OkHttpClient.Builder.through(local: LocalProxy): OkHttpClient.Builder = proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", local.port))).apply {
    if (local.password.isEmpty()) return@apply
    proxyAuthenticator { _, response ->
        // Only the port is answered, which asks before it opens a tunnel: a site at the far end of the tunnel that
        // answers the same way gets no login. And once per request: a login the port refused is not sent again.
        if (response.request.method != "CONNECT" || response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
        val running = localProxy()
        if (running == null || running.port != local.port || running.password != local.password) return@proxyAuthenticator null
        response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(local.user, local.password)).build()
    }
}
