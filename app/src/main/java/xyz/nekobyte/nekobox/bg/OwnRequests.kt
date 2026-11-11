package xyz.nekobyte.nekobox.bg

import kotlinx.coroutines.runBlocking
import libcore.HTTPClient
import okhttp3.Credentials
import okhttp3.OkHttpClient
import xyz.nekobyte.nekobox.aidl.LocalProxy
import xyz.nekobyte.nekobox.ktx.app
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

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
 * Whether the start this port was reported for still runs. It has then held the port ever since, so a connection
 * made to the port meanwhile has reached that start and nothing else. A failure to ask is no yes.
 */
private fun LocalProxy.stillRuns() = runCatching { localProxy()?.start == start }.getOrDefault(false)

/**
 * Sends this client's requests through the running proxy when there is one to go through: see [localProxy]. The
 * client keeps the port and the login for all it does afterwards. For every connection it makes there it asks,
 * before it sends a byte, whether the start it was set up in still runs: see [stillRuns]. Once that start is gone
 * the client's requests fail. They go neither to whatever holds the port by then nor around the proxy. A request the
 * proxy cannot connect fails as well: none goes around a proxy that runs.
 */
fun HTTPClient.throughRunningProxy() {
    val local = localProxy() ?: return
    // Nothing may be thrown at the core, which asks.
    throughSocks5(local.port, local.user, local.password) { local.stillRuns() }
}

/**
 * Sends this client's requests through [local], the local port the running proxy reported, as an HTTP proxy. Like
 * [throughRunningProxy], the client asks for every connection it makes there, before anything is sent on it,
 * whether the start it was set up in still runs, and fails the request when it does not. So whatever takes the port
 * after a stop is sent nothing, and the login goes only to the start it belongs to.
 */
fun OkHttpClient.Builder.through(local: LocalProxy): OkHttpClient.Builder = proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", local.port)))
    .socketFactory(
        object : SocketFactory() {
            // The client connects the sockets it is given itself, and asks for no other kind.
            override fun createSocket() = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    super.connect(endpoint, timeout)
                    if (local.stillRuns()) return
                    close()
                    throw IOException("The proxy this request was set up to go through no longer runs")
                }
            }

            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        },
    ).apply {
        if (local.password.isEmpty()) return@apply
        proxyAuthenticator { _, response ->
            // Only the port is answered, which asks before it opens a tunnel: a site at the far end of the tunnel
            // that answers the same way gets no login. And once per request: a login the port refused is not sent
            // again.
            if (response.request.method != "CONNECT" || response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
            response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(local.user, local.password)).build()
        }
    }
