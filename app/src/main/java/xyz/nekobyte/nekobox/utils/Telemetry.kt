package xyz.nekobyte.nekobox.utils

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
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.database.preference.PublicDatabase
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ConnectionOutcome { CONNECTED, NO_PROFILE, DNS_ERROR, PLUGIN_MISSING, PREPARE_ERROR, START_ERROR, PROCESS_EXIT }
internal enum class DiagnosticOperation { SERVICE_PREPARE, SERVICE_START, SERVICE_RELOAD, EXTERNAL_PROCESS }

internal class ConnectionAttempt(private val report: (ConnectionOutcome, Long) -> Unit = Telemetry::connectionResult) {
    private val startedAt = SystemClock.elapsedRealtime()
    private val completed = AtomicBoolean(false)

    fun complete(outcome: ConnectionOutcome) {
        if (completed.compareAndSet(false, true)) report(outcome, SystemClock.elapsedRealtime() - startedAt)
    }
}

private const val MAX_CONNECTION_DURATION_MS = 300_000L

internal fun diagnosticDue(previous: Long?, now: Long) = previous == null || now - previous >= 60_000L

internal fun diagnosticAllowed(generation: AtomicBoolean, current: AtomicBoolean, enabled: Boolean) = generation === current && generation.get() && enabled

object Telemetry : OnPreferenceDataStoreChangeListener {
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, "Telemetry").apply { isDaemon = true } } }

    @Volatile
    private var consent = AtomicBoolean(false)
    private var mainProcess = false

    // close() keeps the SDK executors alive for setup(), so retain and reuse their owner.
    private var analytics: PostHogInterface? = null
    private var analyticsHttp: OkHttpClient? = null
    private val analyticsCache by lazy { File(app.cacheDir, if (mainProcess) "telemetry-posthog" else "telemetry-posthog-service") }
    private val lastFailureReport = mutableMapOf<DiagnosticOperation, Long>()

    internal fun connectionResult(outcome: ConnectionOutcome, durationMillis: Long) = diagnostic {
        analytics?.capture(
            "connection_result",
            properties = mapOf("outcome" to outcome.name.lowercase(), "duration_ms" to durationMillis.coerceIn(0, MAX_CONNECTION_DURATION_MS)),
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
            Sentry.withScope { scope ->
                scope.setTag("operation", operation.name.lowercase())
                Sentry.captureException(error)
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

        if (BuildConfig.POSTHOG_PROJECT_TOKEN.isNotBlank()) {
            try {
                analyticsHttp = telemetryHttpClient(allowed)
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
                SentryAndroid.init(app) { options -> sentryOptions(BuildConfig.SENTRY_DSN, options, allowed) }
            } catch (_: Exception) {
                Logs.w("Sentry initialization failed")
            }
        }
    }

    private fun stop() {
        consent.set(false)
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
                            val outcome = event.properties?.get("outcome") as? String ?: return@runCatching null
                            if (ConnectionOutcome.entries.none { it.name.lowercase() == outcome }) return@runCatching null
                            val duration = (event.properties?.get("duration_ms") as? Long)
                                ?.takeIf { it in 0..MAX_CONNECTION_DURATION_MS } ?: return@runCatching null
                            mapOf("outcome" to outcome, "duration_ms" to duration)
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

internal fun sentryOptions(dsn: String, options: SentryAndroidOptions = SentryAndroidOptions(), allowed: () -> Boolean): SentryAndroidOptions {
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
        setTransportGate { allowed() }
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
        platform = "java"
        setTag("android_api", Build.VERSION.SDK_INT.toString())
        event.getTag("operation")?.takeIf { value -> DiagnosticOperation.entries.any { it.name.lowercase() == value } }
            ?.let { setTag("operation", it) }
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
