package xyz.nekobyte.nekobox.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogMemoryPreferences
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.UncaughtExceptionHandlerIntegration
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.database.preference.PublicDatabase
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.group.ImportFormat
import xyz.nekobyte.nekobox.group.ImportPreview
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ConnectionOutcome { CONNECTED, NO_PROFILE, DNS_ERROR, PLUGIN_MISSING, PREPARE_ERROR, START_ERROR, PROCESS_EXIT }
internal enum class DiagnosticOperation { SERVICE_PREPARE, SERVICE_START, SERVICE_RELOAD, EXTERNAL_PROCESS, CORE_PANIC }

/** The kind of a failed start, read from its messages on the device. Only the kind is reported. */
internal enum class FailureCategory { INVALID_SETTINGS, TLS, DNS, TIMEOUT, REFUSED, PORT_IN_USE, PERMISSION, OTHER }

internal fun failureCategory(error: Throwable): FailureCategory {
    val text = generateSequence(error) { it.cause }.take(8).mapNotNull { it.message }.joinToString("\n").lowercase()
    fun has(vararg words: String) = words.any { it in text }
    return when {
        has("address already in use") -> FailureCategory.PORT_IN_USE
        has("permission denied", "operation not permitted") -> FailureCategory.PERMISSION
        has("x509", "certificate", "tls:", "handshake") -> FailureCategory.TLS
        has("no such host", "lookup ", "dns") -> FailureCategory.DNS
        has("timeout", "timed out", "deadline exceeded") -> FailureCategory.TIMEOUT
        has("connection refused") -> FailureCategory.REFUSED
        has("invalid", "initialize outbound", "initialize endpoint", "parse", "unknown field", "missing", "required", "unsupported") -> FailureCategory.INVALID_SETTINGS
        else -> FailureCategory.OTHER
    }
}

/** Protocol, transport and security of a profile as fixed names; never its server, credentials or name. */
internal data class ProfileKind(val protocol: String, val transport: String? = null, val security: String? = null)

private val PROTOCOL_NAMES = mapOf(
    ProxyEntity.TYPE_SOCKS to "socks", ProxyEntity.TYPE_HTTP to "http", ProxyEntity.TYPE_SS to "shadowsocks",
    ProxyEntity.TYPE_SSR to "shadowsocksr", ProxyEntity.TYPE_VMESS to "vmess", ProxyEntity.TYPE_TROJAN to "trojan",
    ProxyEntity.TYPE_MIERU to "mieru", ProxyEntity.TYPE_NAIVE to "naive", ProxyEntity.TYPE_HYSTERIA to "hysteria2",
    ProxyEntity.TYPE_SSH to "ssh", ProxyEntity.TYPE_WG to "wireguard", ProxyEntity.TYPE_TUIC to "tuic",
    ProxyEntity.TYPE_JUICITY to "juicity", ProxyEntity.TYPE_SHADOWTLS to "shadowtls", ProxyEntity.TYPE_ANYTLS to "anytls",
    ProxyEntity.TYPE_CHAIN to "chain", ProxyEntity.TYPE_CONFIG to "custom", ProxyEntity.TYPE_SNELL to "snell",
    ProxyEntity.TYPE_AWG to "amneziawg", ProxyEntity.TYPE_TAILSCALE to "tailscale",
)
internal val REPORTED_PROTOCOLS = PROTOCOL_NAMES.values.toSet() + setOf("vless", "other")
internal val REPORTED_TRANSPORTS = setOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp", "splithttp", "kcp", "quic")
internal val REPORTED_SECURITIES = setOf("none", "tls", "reality")
internal val COUNT_BUCKETS = listOf("0", "1-9", "10-99", "100+")

internal fun profileKind(profile: ProxyEntity): ProfileKind {
    val v2ray = runCatching { profile.requireBean() }.getOrNull() as? StandardV2RayBean
    val protocol = if (v2ray?.isVLESS == true) "vless" else PROTOCOL_NAMES[profile.type] ?: "other"
    // A REALITY profile stores security "tls" together with the REALITY public key.
    val security = if (v2ray?.security == "tls" && !v2ray.realityPubKey.isNullOrBlank()) "reality" else v2ray?.security?.ifBlank { "none" }
    return ProfileKind(
        protocol,
        v2ray?.type?.ifBlank { "tcp" }?.takeIf { it in REPORTED_TRANSPORTS },
        security?.takeIf { it in REPORTED_SECURITIES },
    )
}

internal fun countBucket(count: Int) = when {
    count <= 0 -> "0"
    count < 10 -> "1-9"
    count < 100 -> "10-99"
    else -> "100+"
}

internal data class ConnectionReport(val outcome: ConnectionOutcome, val durationMillis: Long, val kind: ProfileKind?, val category: FailureCategory?)

internal class ConnectionAttempt(private val report: (ConnectionReport) -> Unit = Telemetry::connectionResult) {
    private val startedAt = SystemClock.elapsedRealtime()
    private val completed = AtomicBoolean(false)

    // Set once the profile is known; preparation can fail before that.
    @Volatile
    var kind: ProfileKind? = null

    fun complete(outcome: ConnectionOutcome, error: Throwable? = null) {
        if (completed.compareAndSet(false, true)) {
            report(ConnectionReport(outcome, SystemClock.elapsedRealtime() - startedAt, kind, error?.let(::failureCategory)))
        }
    }
}

/** The file the core writes its fatal panics to, under the cache directory; see InitCore in libcore. */
internal const val CORE_CRASH_FILE = "core-crash.txt"

private val DIGITS = Regex("[0-9]+")

/** The panic in a Go crash dump as a Sentry event: its kind and the crashing goroutine's frames, without values. */
internal fun corePanicEvent(dump: String): SentryEvent? {
    val lines = dump.lines()
    val start = lines.indexOfFirst { it.startsWith("panic: ") || it.startsWith("fatal error: ") }.takeIf { it >= 0 } ?: return null
    val message = lines[start].substringAfter(": ")
    val kind = when {
        lines[start].startsWith("fatal error: ") -> "fatal error: " + message.replace(DIGITS, "N")

        message.startsWith("runtime error: ") -> message.substringBefore('[').replace(DIGITS, "N").trim()

        // Other panic values are arbitrary text, which may hold data.
        else -> "panic"
    }
    val goroutine = (start until lines.size).firstOrNull { lines[it].startsWith("goroutine ") } ?: return null
    val frames = mutableListOf<SentryStackFrame>()
    var index = goroutine + 1
    while (index + 1 < lines.size && lines[index].isNotBlank() && frames.size < 64) {
        // "pkg/path.(*Type).Method(0x1, 0x2)" over "\t/path/file.go:123 +0x45"; the arguments are values and stay out.
        val function = lines[index].removePrefix("created by ").substringBefore(" in goroutine").let { name ->
            if (name.endsWith(")")) name.substringBeforeLast('(') else name
        }
        val location = lines[index + 1].trim().substringBefore(" +")
        val dot = function.indexOf('.', function.lastIndexOf('/') + 1)
        frames += SentryStackFrame().apply {
            module = if (dot > 0) function.substring(0, dot) else function
            this.function = if (dot > 0) function.substring(dot + 1) else function
            filename = location.substringBeforeLast(':').substringAfterLast('/')
            lineno = location.substringAfterLast(':').toIntOrNull()
            isInApp = function.startsWith("libcore") || function.startsWith("github.com/sagernet/sing-box")
        }
        index += 2
    }
    if (frames.isEmpty()) return null
    return SentryEvent().apply {
        level = SentryLevel.FATAL
        setTag("operation", DiagnosticOperation.CORE_PANIC.name.lowercase())
        exceptions = listOf(
            SentryException().apply {
                type = kind
                module = "libcore"
                mechanism = Mechanism().apply {
                    isHandled = false
                    type = "go_panic"
                }
                // Sentry lists the outermost frame first; Go prints the crashing frame first.
                stacktrace = SentryStackTrace(frames.reversed())
            },
        )
    }
}

private const val MAX_CONNECTION_DURATION_MS = 300_000L

internal fun diagnosticDue(previous: Long?, now: Long) = previous == null || now - previous >= 60_000L

internal fun diagnosticAllowed(generation: AtomicBoolean, current: AtomicBoolean, enabled: Boolean) = generation === current && generation.get() && enabled

object Telemetry : OnPreferenceDataStoreChangeListener {
    private val worker by lazy { Executors.newSingleThreadScheduledExecutor { Thread(it, "Telemetry").apply { isDaemon = true } } }

    // Reports leave only through a VPN: sent outside one, their hosts would tell anyone watching the
    // network that this app runs. Error reports wait here, on the worker, for a connection; none survive the process.
    private val held = ArrayDeque<() -> Unit>()
    private const val MAX_HELD = 5

    internal fun tunnelUp(): Boolean = runCatching {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    private fun sendOrHold(send: () -> Unit) {
        if (tunnelUp()) {
            send()
        } else if (held.size < MAX_HELD) {
            held.addLast(send)
        }
    }

    // A new VPN becomes the default network shortly after it connects.
    private fun flushHeldSoon() {
        for (delay in listOf(5L, 60L)) {
            worker.schedule(Runnable { diagnostic { while (held.isNotEmpty() && tunnelUp()) held.removeFirst()() } }, delay, TimeUnit.SECONDS)
        }
    }

    @Volatile
    private var consent = AtomicBoolean(false)
    private var mainProcess = false

    // close() keeps the SDK executors alive for setup(), so retain and reuse their owner.
    private var analytics: PostHogInterface? = null
    private var analyticsHttp: OkHttpClient? = null
    private val analyticsCache by lazy { File(app.cacheDir, if (mainProcess) "telemetry-posthog" else "telemetry-posthog-service") }
    private val lastFailureReport = mutableMapOf<DiagnosticOperation, Long>()

    internal fun connectionResult(report: ConnectionReport) = diagnostic {
        analytics?.capture(
            "connection_result",
            properties = buildMap {
                put("outcome", report.outcome.name.lowercase())
                put("duration_ms", report.durationMillis.coerceIn(0, MAX_CONNECTION_DURATION_MS))
                report.kind?.let { kind ->
                    put("protocol", kind.protocol)
                    kind.transport?.let { put("transport", it) }
                    kind.security?.let { put("security", it) }
                }
                report.category?.let { put("category", it.name.lowercase()) }
            },
        )
    }

    /** The hosts reports go to. NekoBox's VPN routes them through the proxy, whatever the rules say. */
    internal fun reportHosts(
        postHogHost: String? = BuildConfig.POSTHOG_HOST.takeIf { BuildConfig.POSTHOG_PROJECT_TOKEN.isNotBlank() },
        sentryDsn: String = BuildConfig.SENTRY_DSN,
    ) = listOfNotNull(postHogHost, postHogHost?.let(::postHogAssetsHost), sentryDsn).mapNotNull { it.toHttpUrlOrNull()?.host }.distinct()

    // On PostHog's own hosts the SDK fetches its remote config from a separate assets host.
    private fun postHogAssetsHost(host: String) = when (host.removeSuffix("/")) {
        PostHogConfig.DEFAULT_US_HOST -> PostHogConfig.DEFAULT_US_ASSETS_HOST
        PostHogConfig.DEFAULT_EU_HOST -> PostHogConfig.DEFAULT_EU_ASSETS_HOST
        else -> null
    }

    internal fun importOutcome(format: ImportFormat?, preview: ImportPreview) = diagnostic {
        analytics?.capture(
            "subscription_import",
            properties = buildMap {
                put("format", format?.name?.lowercase() ?: "unknown")
                put("accepted", countBucket(preview.acceptedCount))
                put("refused", countBucket(preview.unparsedCount))
                // Protocol names come from the subscription; anything not on the list is "unknown".
                val refused = preview.failed.map { it?.lowercase()?.takeIf { name -> name in REPORTED_PROTOCOLS } ?: "unknown" }.toSortedSet()
                if (refused.isNotEmpty()) put("refused_protocols", refused.joinToString(","))
                if (preview.dropped.isNotEmpty()) put("dropped_sections", preview.dropped.map { it.name.lowercase() }.sorted().joinToString(","))
            },
        )
    }

    internal fun connectionInterrupted() = diagnostic {
        analytics?.capture("connection_interrupted")
    }

    internal fun reportFailure(operation: DiagnosticOperation, error: Throwable) {
        if (error is CancellationException) return
        diagnostic {
            val now = SystemClock.elapsedRealtime()
            if (!diagnosticDue(lastFailureReport[operation], now)) return@diagnostic
            lastFailureReport[operation] = now
            val category = failureCategory(error).name.lowercase()
            sendOrHold {
                Sentry.withScope { scope ->
                    scope.setTag("operation", operation.name.lowercase())
                    scope.setTag("category", category)
                    Sentry.captureException(error)
                }
            }
        }
    }

    private fun diagnostic(send: () -> Unit) {
        if (BuildConfig.POSTHOG_PROJECT_TOKEN.isBlank() && BuildConfig.SENTRY_DSN.isBlank()) return
        val generation = consent
        if (!runCatching { diagnosticAllowed(generation, consent, DataStore.helpToImprove) }.getOrDefault(false)) return
        worker.execute {
            try {
                if (!diagnosticAllowed(generation, consent, DataStore.helpToImprove)) return@execute
                send()
            } catch (_: Exception) {
                Logs.w("Telemetry report failed")
            }
        }
    }

    fun initialize(isMainProcess: Boolean) {
        if (BuildConfig.POSTHOG_PROJECT_TOKEN.isBlank() && BuildConfig.SENTRY_DSN.isBlank()) return
        mainProcess = isMainProcess
        DataStore.configurationStore.registerChangeListener(this)
        PublicDatabase.database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer("KeyValuePair") {
                override fun onInvalidated(tables: Set<String>) {
                    worker.execute {
                        try {
                            // Settings also change in the other process and during backup restore.
                            DataStore.configurationStore.refreshBlocking()
                            update()
                        } catch (_: Exception) {
                            stop()
                        }
                    }
                }
            },
        )
        onPreferenceDataStoreChanged(DataStore.configurationStore, Key.HELP_TO_IMPROVE)
        // Held reports go out once a VPN carries the traffic, whichever app runs it.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = flushHeldSoon()
                },
            )
        }.onFailure { Logs.w("Telemetry network callback failed") }
        // The core runs in the service process and leaves its last panic there.
        if (!isMainProcess) worker.execute { reportCoreCrash() }
    }

    private fun reportCoreCrash() {
        val file = File(app.cacheDir, CORE_CRASH_FILE)
        val dump = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        // The core keeps the file open for appending, so it is emptied, not deleted.
        runCatching { RandomAccessFile(file, "rw").use { it.setLength(0) } }
        val event = corePanicEvent(dump) ?: return
        diagnostic { sendOrHold { Sentry.captureEvent(event) } }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.HELP_TO_IMPROVE) return
        // Revoke this generation before asynchronous SDK shutdown can flush anything.
        if (!DataStore.helpToImprove) consent.set(false)
        worker.execute { update() }
    }

    private fun update() {
        if (!DataStore.helpToImprove) {
            stop()
            return
        }
        if (consent.get()) return
        stop()
        val permission = AtomicBoolean(true)
        consent = permission
        val allowed = { permission.get() && runCatching { DataStore.helpToImprove }.getOrDefault(false) }
        val sendable = { allowed() && tunnelUp() }

        if (BuildConfig.POSTHOG_PROJECT_TOKEN.isNotBlank()) {
            try {
                // A failed upload keeps its events queued for a later try.
                analyticsHttp = telemetryHttpClient(sendable)
                analytics = setupPostHog(analytics, postHogConfig(BuildConfig.POSTHOG_PROJECT_TOKEN, BuildConfig.POSTHOG_HOST, analyticsCache, analyticsHttp!!, allowed))
                if (mainProcess) analytics?.capture("app_opened")
            } catch (_: Exception) {
                Logs.w("PostHog initialization failed")
            }
        }
        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            try {
                // Sentry swallows configuration callback errors, so validate before entering it.
                require(BuildConfig.SENTRY_DSN.toHttpUrlOrNull()?.isHttps == true)
                SentryAndroid.init(app) { options -> sentryOptions(BuildConfig.SENTRY_DSN, options, transport = sendable, allowed = allowed) }
            } catch (_: Exception) {
                Logs.w("Sentry initialization failed")
            }
        }
    }

    private fun stop() {
        consent.set(false)
        held.clear()
        analyticsHttp?.dispatcher?.cancelAll()
        analytics?.optOut()
        analytics?.close()
        analyticsHttp?.connectionPool?.evictAll()
        analyticsHttp = null
        if (!analyticsCache.deleteRecursively()) Logs.w("PostHog cache cleanup failed")
        Sentry.close()
    }
}

internal fun telemetryHttpClient(allowed: () -> Boolean) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        if (!allowed()) throw IOException("Telemetry disabled")
        chain.proceed(chain.request())
    }
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun setupPostHog(existing: PostHogInterface?, config: PostHogConfig) = existing?.apply { setup(config) } ?: PostHog.with(config)

internal fun postHogConfig(token: String, host: String, cache: File, client: OkHttpClient, allowed: () -> Boolean): PostHogConfig {
    val url = requireNotNull(host.toHttpUrlOrNull()) { "Invalid PostHog host" }
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    require(token.isNotBlank() && '/' !in token && '\\' !in token && token != "." && token != "..")
    return PostHogConfig(token, host).apply {
        // The core SDK requires a file queue; a new directory prevents replaying old sessions.
        storagePrefix = File(cache, UUID.randomUUID().toString()).absolutePath
        // Reusing the SDK must not reuse its previous identity or stored opt-out value.
        cachePreferences = PostHogMemoryPreferences()
        // beforeSend does not cover SDK configuration requests.
        httpClient = client
        personProfiles = PersonProfiles.NEVER
        preloadFeatureFlags = false
        setDefaultPersonProperties = false
        sendFeatureFlagEvent = false
        sessionReplay = false
        surveys = false
        errorTrackingConfig.autoCapture = false
        maxQueueSize = 20
        flushAt = 1
        addBeforeSend { event ->
            runCatching {
                if (allowed()) {
                    val diagnosticProperties = when (event.event) {
                        "app_opened", "connection_interrupted" -> emptyMap()

                        "connection_result" -> {
                            val properties = event.properties.orEmpty()
                            val outcome = properties["outcome"] as? String ?: return@runCatching null
                            if (ConnectionOutcome.entries.none { it.name.lowercase() == outcome }) return@runCatching null
                            val duration = (properties["duration_ms"] as? Long)
                                ?.takeIf { it in 0..MAX_CONNECTION_DURATION_MS } ?: return@runCatching null
                            val optional = mapOf(
                                "protocol" to REPORTED_PROTOCOLS,
                                "transport" to REPORTED_TRANSPORTS,
                                "security" to REPORTED_SECURITIES,
                                "category" to FailureCategory.entries.map { it.name.lowercase() }.toSet(),
                            )
                            val extra = optional.keys.filter { it in properties }.associateWith { properties[it] as? String }
                            if (extra.any { (key, value) -> value == null || value !in optional.getValue(key) }) return@runCatching null
                            mapOf("outcome" to outcome, "duration_ms" to duration) + extra.mapValues { it.value!! }
                        }

                        "subscription_import" -> {
                            val properties = event.properties.orEmpty()
                            val format = properties["format"] as? String ?: return@runCatching null
                            if (format != "unknown" && ImportFormat.entries.none { it.name.lowercase() == format }) return@runCatching null
                            val accepted = (properties["accepted"] as? String)?.takeIf { it in COUNT_BUCKETS } ?: return@runCatching null
                            val refused = (properties["refused"] as? String)?.takeIf { it in COUNT_BUCKETS } ?: return@runCatching null
                            val protocols = properties["refused_protocols"] as? String
                            val sections = properties["dropped_sections"] as? String
                            val sectionNames = ImportPreview.Section.entries.map { it.name.lowercase() }.toSet()
                            if (protocols?.split(',')?.all { it in REPORTED_PROTOCOLS || it == "unknown" } == false) return@runCatching null
                            if (sections?.split(',')?.all { it in sectionNames } == false) return@runCatching null
                            mapOf("format" to format, "accepted" to accepted, "refused" to refused) +
                                listOfNotNull(protocols?.let { "refused_protocols" to it }, sections?.let { "dropped_sections" to it })
                        }

                        else -> return@runCatching null
                    }
                    PostHogEvent(
                        event = event.event,
                        distinctId = event.distinctId,
                        timestamp = event.timestamp,
                        uuid = event.uuid,
                        properties = mutableMapOf<String, Any>(
                            "app_version" to BuildConfig.VERSION_NAME,
                            "app_build" to BuildConfig.VERSION_CODE,
                            "android_api" to Build.VERSION.SDK_INT,
                            "build_type" to BuildConfig.BUILD_TYPE,
                            "\$geoip_disable" to true,
                            "\$process_person_profile" to false,
                        ).apply { putAll(diagnosticProperties) },
                    )
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}

internal fun sentryOptions(
    dsn: String,
    options: SentryAndroidOptions = SentryAndroidOptions(),
    transport: (() -> Boolean)? = null,
    allowed: () -> Boolean,
): SentryAndroidOptions {
    require(dsn.toHttpUrlOrNull()?.isHttps == true) { "Sentry requires an HTTPS DSN" }
    return options.apply {
        this.dsn = dsn
        addInAppInclude("xyz.nekobyte.nekobox")
        release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        environment = BuildConfig.BUILD_TYPE
        dataCollection.userInfo = false
        isAttachServerName = false
        isAttachThreads = false
        isEnableAutoSessionTracking = false
        isEnableShutdownHook = false
        isSendClientReports = false
        // Keep only the crash-handler wrapper; Android's other integrations collect extra data.
        integrations.removeAll { it !is UncaughtExceptionHandlerIntegration }
        enableAllAutoBreadcrumbs(false)
        isAnrEnabled = false
        isEnableNdk = false
        isEnableScopeSync = false
        isEnableAutoActivityLifecycleTracing = false
        isEnableFramesTracking = false
        isEnablePerformanceV2 = false
        isCollectAdditionalContext = false
        isEnableRootCheck = false
        isAttachScreenshot = false
        isAttachViewHierarchy = false
        isAttachAnrThreadDump = false
        isAttachRawTombstone = false
        tracesSampleRate = null
        maxBreadcrumbs = 0
        // No disk queue can survive an opt-out or be uploaded by a later launch.
        cacheDirPath = null
        flushTimeoutMillis = 1500
        shutdownTimeoutMillis = 0
        setTransportGate { (transport ?: allowed)() }
        setBeforeSend { event, _ ->
            runCatching { if (allowed()) sanitizedCrashEvent(event) else null }.getOrNull()
        }
    }
}

internal fun sanitizedCrashEvent(event: SentryEvent): SentryEvent? {
    val exceptions = event.exceptions?.takeIf { it.isNotEmpty() } ?: return null
    val handled = exceptions.none { it.mechanism?.isHandled == false }
    // Copy an allowlist instead of redacting messages that can contain entire VPN configs.
    return SentryEvent(event.timestamp).apply {
        eventId = event.eventId
        level = event.level
        release = event.release
        environment = event.environment
        val operation = event.getTag("operation")?.takeIf { value -> DiagnosticOperation.entries.any { it.name.lowercase() == value } }
        platform = if (operation == DiagnosticOperation.CORE_PANIC.name.lowercase()) "go" else "java"
        setTag("android_api", Build.VERSION.SDK_INT.toString())
        operation?.let { setTag("operation", it) }
        event.getTag("category")?.takeIf { value -> FailureCategory.entries.any { it.name.lowercase() == value } }
            ?.let { setTag("category", it) }
        this.exceptions = exceptions.map { exception ->
            SentryException().apply {
                type = exception.type
                module = exception.module
                mechanism = Mechanism().apply {
                    isHandled = handled
                    type = if (isHandled == true) "generic" else "UncaughtExceptionHandler"
                }
                stacktrace = exception.stacktrace?.let { trace ->
                    SentryStackTrace(
                        trace.frames?.map { frame ->
                            SentryStackFrame().apply {
                                module = frame.module
                                function = frame.function
                                filename = frame.filename?.substringAfterLast('/')?.substringAfterLast('\\')
                                lineno = frame.lineno
                                isInApp = frame.isInApp
                            }
                        },
                    )
                }
            }
        }
    }
}
