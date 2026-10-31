package xyz.nekobyte.nekobox.bg.proto

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import libcore.BoxInstance
import libcore.Libcore
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.GuardedProcessPool
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.LOCALHOST
import xyz.nekobyte.nekobox.fmt.buildConfig
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean
import xyz.nekobyte.nekobox.fmt.mieru.buildMieruConfig
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean
import xyz.nekobyte.nekobox.fmt.naive.buildNaiveConfig
import xyz.nekobyte.nekobox.fmt.tailscale.acquireTailscaleState
import xyz.nekobyte.nekobox.fmt.tailscale.pruneTailscaleState
import xyz.nekobyte.nekobox.ktx.*
import xyz.nekobyte.nekobox.net.LocalResolverImpl
import xyz.nekobyte.nekobox.plugin.PluginManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

// Standalone probes have no service socket when the VPN is stopped.
internal fun mieruEnvironment(configFile: File) = mutableMapOf("MIERU_CONFIG_JSON_FILE" to configFile.absolutePath).apply {
    if (DataStore.vpnService != null || DataStore.serviceState != BaseService.State.Stopped) {
        put("MIERU_PROTECT_PATH", "protect_path")
    }
}

abstract class BoxInstance(val profile: ProxyEntity) : Closeable {
    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance
    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, String>()
    open lateinit var processes: GuardedProcessPool
    private val cacheFiles = ArrayList<File>()
    private var stateLease: Closeable? = null

    fun isInitialized() = ::config.isInitialized && ::box.isInitialized

    protected fun initPlugin(name: String) = pluginPath.getOrPut(name) { PluginManager.init(name)!! }

    protected open fun buildConfig() {
        config = buildConfig(profile)
        DataStore.mixedInboundAuthed = DataStore.mixedInboundNeedsAuth
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    /**
     * What the profile's server name stands for, looked up by this core with the resolver its
     * connection uses (Direct DNS, or the subscription's own), and kept for the row's country on
     * Home. An address needs no lookup; null when the lookup fails.
     */
    fun lookUpServer(): String? {
        val name = profile.requireBean().serverAddress?.takeIf { it.isNotBlank() } ?: return null
        if (name.isIpAddress()) return name
        val tag = config.profileTagMap[profile.id] ?: return null
        val address = try {
            Libcore.resolveServer(box, tag, name).lineSequence().firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            Logs.w(e)
            null
        } ?: return null
        DataStore.rememberServerAddresses(mapOf(name to address))
        return address
    }

    open suspend fun init() {
        buildConfig()
        val nodes = config.tailscaleEndpoints.keys
        if (nodes.isNotEmpty()) {
            stateLease = acquireTailscaleState(nodes)
            // Config construction may precede queued probe serialization or a restore/reset.
            // Never run its old JSON against a newly assigned identity at the same numeric ID.
            val snapshots = config.trafficMap.values.flatten().associateBy { it.id }
            for (id in nodes) {
                val snapshot = snapshots[id] ?: error("Missing Tailscale profile snapshot")
                val current = ProfileDatabase.proxyDao.getById(id)
                check(current?.type == ProxyEntity.TYPE_TAILSCALE && current.uuid == snapshot.uuid && current.requireBean() == snapshot.requireBean()) {
                    "Tailscale profile changed. Retry with the saved profile."
                }
            }
        }
        currentCoroutineContext().ensureActive()
        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                when (val bean = profile.requireBean()) {
                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        val creds = checkNotNull(config.localProxyCredentials[port]) { "Missing Mieru SOCKS credentials" }
                        pluginConfigs[port] = bean.buildMieruConfig(port, creds.first, creds.second)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        val creds = config.localProxyCredentials[port]
                        pluginConfigs[port] = bean.buildNaiveConfig(port, creds?.first, creds?.second)
                    }
                }
            }
        }
        loadConfig()
    }

    internal fun writeSidecarFile(directory: File, prefix: String, suffix: String, content: String): File {
        val file = File.createTempFile(prefix, suffix, directory)
        cacheFiles.add(file)
        file.writeText(content)
        return file
    }

    open fun launch() {
        val cacheDir = File(NekoBox.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()
        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                val config = pluginConfigs[port].orEmpty()
                when (val bean = profile.requireBean()) {
                    is MieruBean -> {
                        val configFile = writeSidecarFile(cacheDir, "mieru-", ".json", config)
                        processes.start(
                            listOf(initPlugin("mieru-plugin").path, "run"),
                            mieruEnvironment(configFile),
                        )
                    }

                    is NaiveBean -> {
                        val configFile = writeSidecarFile(cacheDir, "naive-", ".json", config)
                        val env = mutableMapOf<String, String>()
                        bean.certificates?.takeIf { it.isNotBlank() }?.let { certificates ->
                            val certFile = writeSidecarFile(cacheDir, "naive-", ".crt", certificates)
                            env["SSL_CERT_FILE"] = certFile.absolutePath
                        }
                        processes.start(listOf(initPlugin("naive-plugin").path, configFile.absolutePath), env)
                    }
                }
            }
        }
        box.start()
    }

    private suspend fun pendingExternalPorts(ports: Collection<Int>, timeoutMillis: Long) = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val pending = ports.toMutableSet()
        while (pending.isNotEmpty() && SystemClock.elapsedRealtime() < deadline && processes.isActive) {
            ensureActive()
            if (!processes.isActive) break
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val port = iterator.next()
                try {
                    Socket().use { it.connect(InetSocketAddress(LOCALHOST, port), 100) }
                    iterator.remove()
                } catch (_: IOException) {
                }
            }
            if (pending.isNotEmpty()) {
                if (!processes.isActive) break
                delay(50)
            }
        }
        pending
    }

    suspend fun awaitExternalProcessesReady(strict: Boolean = false) {
        if (!::processes.isInitialized || processes.processCount == 0) return
        val ports = config.externalIndex.flatMap { it.chain.keys }.distinct()
        if (ports.isEmpty()) return
        val timeout = DataStore.connectionTestTimeout.toLong().coerceAtLeast(1_000L)
        val pending = pendingExternalPorts(ports, if (strict) minOf(2_000L, timeout) else timeout)
        if (pending.isEmpty()) return
        if (!processes.isActive) {
            if (strict) throw IOException("sidecar process pool stopped before listener readiness")
            Logs.w("sidecar listener not ready on port(s): ${pending.joinToString()}; process pool already stopped")
            return
        }
        val message = "sidecar listener not ready on port(s): ${pending.joinToString()}"
        if (strict) throw IOException(message)
        Logs.w("$message; continuing (sing-box will retry the connection)")
    }

    // The child owns initialization and JNI calls. Cancellation joins it before close, so a
    // native object published late cannot escape cleanup or outlive its state lease.
    internal suspend fun <T> runProbe(query: suspend () -> T): T = withContext(Dispatchers.IO) {
        supervisorScope {
            val failure = CompletableDeferred<Nothing>()
            processes = GuardedProcessPool { failure.completeExceptionally(it) }
            val worker = async {
                init()
                ensureActive()
                this@BoxInstance.launch()
                ensureActive()
                awaitExternalProcessesReady(strict = true)
                ensureActive()
                if (failure.isCompleted) failure.await()
                query()
            }
            var ended: Throwable? = null
            try {
                select {
                    failure.onAwait { it }
                    worker.onAwait { it }
                }
            } catch (e: Throwable) {
                ended = e
                throw e
            } finally {
                withContext(NonCancellable) {
                    worker.cancelAndJoin()
                    try {
                        close()
                    } catch (e: Exception) {
                        // A cleanup error is the outcome of a successful query, but it must not
                        // replace the failure that ended the probe.
                        ended?.addSuppressed(e) ?: throw e
                    } finally {
                        processes.coroutineContext[Job]?.join()
                    }
                }
            }
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        try {
            try {
                if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)
            } finally {
                if (::box.isInitialized) box.close()
            }
        } finally {
            cacheFiles.removeAll {
                it.delete()
                true
            }
            stateLease?.close()
            stateLease = null
        }
        if (::config.isInitialized && config.tailscaleEndpoints.isNotEmpty()) {
            try {
                pruneTailscaleState(profileIds = config.tailscaleEndpoints.keys)
            } catch (_: Exception) {
                // Best-effort housekeeping must not replace the probe result or its failure.
                Logs.w("Tailscale state cleanup deferred")
            }
        }
    }
}
