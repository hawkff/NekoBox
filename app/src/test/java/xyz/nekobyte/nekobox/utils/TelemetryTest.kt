package xyz.nekobyte.nekobox.utils

import android.app.Application
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.room.Room
import com.google.gson.JsonParser
import com.posthog.PersonProfiles
import com.posthog.PostHogEvent
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.UncaughtExceptionHandlerIntegration
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import io.sentry.transport.NoOpTransport
import io.sentry.util.Platform
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.preference.PublicDatabase
import xyz.nekobyte.nekobox.database.preference.RoomPreferenceDataStore
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class TelemetryTest {
    private val analyticsCache get() = RuntimeEnvironment.getApplication().cacheDir.resolve("telemetry-posthog")

    @Test
    fun reportHostsComeFromTheConfiguredServices() {
        assertEquals(
            listOf("us.i.posthog.com", "us-assets.i.posthog.com", "o1.ingest.us.sentry.io"),
            Telemetry.reportHosts("https://us.i.posthog.com", "https://key@o1.ingest.us.sentry.io/2"),
        )
        // PostHog's own hosts serve the SDK's remote config from an assets host; other hosts serve it themselves.
        assertEquals(listOf("eu.i.posthog.com", "eu-assets.i.posthog.com"), Telemetry.reportHosts("https://eu.i.posthog.com/", ""))
        assertEquals(listOf("relay.example"), Telemetry.reportHosts("https://relay.example", "https://key@relay.example/2"))
        // Without a key a service gets no rule.
        assertEquals(emptyList<String>(), Telemetry.reportHosts(null, ""))
    }

    @Test
    fun switchDefaultsOffAndPersistsTheChoiceAcrossInflation() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomPreferenceDataStore(db.keyValuePairDao())
            fun preference(): SwitchPreference {
                val manager = PreferenceManager(context).apply { preferenceDataStore = store }
                val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
                return requireNotNull(screen.findPreference(Key.HELP_TO_IMPROVE))
            }
            assertFalse(preference().isChecked)
            preference().isChecked = true
            assertTrue(store.getBoolean(Key.HELP_TO_IMPROVE, false))
            assertTrue(preference().isChecked)
            preference().isChecked = false
            assertFalse(preference().isChecked)
        } finally {
            db.close()
        }
    }

    @Test
    fun analyticsUsesAnAllowlistAndHonorsOptOut() {
        var enabled = true
        val config = postHogConfig("phc_example", "https://us.i.posthog.com", analyticsCache, telemetryHttpClient { enabled }) { enabled }
        assertEquals(PersonProfiles.NEVER, config.personProfiles)
        assertFalse(config.preloadFeatureFlags)
        assertFalse(config.sessionReplay)
        assertFalse(config.errorTrackingConfig.autoCapture)
        val filter = config.beforeSendList.single()
        val event = PostHogEvent("app_opened", "random-session", mutableMapOf("profile" to "private", "\$ip" to "192.0.2.1"))
        val safe = requireNotNull(filter.run(event))
        assertEquals(event.distinctId, safe.distinctId)
        assertEquals(
            setOf("app_version", "app_build", "android_api", "build_type", "\$geoip_disable", "\$process_person_profile"),
            safe.properties?.keys,
        )
        assertEquals(true, safe.properties?.get("\$geoip_disable"))
        assertEquals(false, safe.properties?.get("\$process_person_profile"))
        assertNull(filter.run(event.copy(event = "\$screen")))
        enabled = false
        assertNull(filter.run(event))
    }

    @Test
    fun diagnosticEventsKeepOnlyKnownOutcomesAndBoundedDurations() {
        var enabled = true
        val config = postHogConfig("phc_example", "https://example.com", analyticsCache, telemetryHttpClient { enabled }) { enabled }
        val filter = config.beforeSendList.single()
        assertEquals(1, config.flushAt)
        for (outcome in ConnectionOutcome.entries) {
            val event = PostHogEvent(
                "connection_result",
                "random-session",
                mutableMapOf("outcome" to outcome.name.lowercase(), "duration_ms" to 321L, "profile" to "private profile"),
            )
            val safe = requireNotNull(filter.run(event))
            assertEquals(outcome.name.lowercase(), safe.properties?.get("outcome"))
            assertEquals(321L, safe.properties?.get("duration_ms"))
            assertNull(safe.properties?.get("profile"))
            assertEquals(8, safe.properties?.size)
            assertNull(filter.run(event.copy(properties = mutableMapOf("outcome" to "private endpoint", "duration_ms" to 321L))))
            for (duration in listOf(-1L, 300_001L, "private value", Double.NaN)) {
                assertNull(filter.run(event.copy(properties = mutableMapOf("outcome" to outcome.name.lowercase(), "duration_ms" to duration))))
            }
            enabled = false
            assertNull(filter.run(event))
            enabled = true
        }
        val interrupted = PostHogEvent("connection_interrupted", "random-session", mutableMapOf("message" to "private config"))
        assertEquals(6, requireNotNull(filter.run(interrupted)).properties?.size)
        enabled = false
        assertNull(filter.run(interrupted))
    }

    @Test
    fun profileKindNamesRealitySecurity() {
        fun kind(security: String, realityKey: String = "") = profileKind(
            ProxyEntity().putBean(
                VMessBean().applyDefaultValues().apply {
                    alterId = -1
                    this.security = security
                    realityPubKey = realityKey
                },
            ),
        )
        assertEquals(ProfileKind("vless", "tcp", "reality"), kind("tls", "fictional-reality-public-key"))
        assertEquals(ProfileKind("vless", "tcp", "tls"), kind("tls"))
        assertEquals(ProfileKind("vless", "tcp", "none"), kind(""))
    }

    @Test
    fun connectionResultsCarryOnlyListedProtocolsAndFailureKinds() {
        val filter = postHogConfig("phc_example", "https://example.com", analyticsCache, telemetryHttpClient { true }) { true }.beforeSendList.single()
        val base = mapOf<String, Any>("outcome" to "start_error", "duration_ms" to 10L)
        val listed = base + mapOf("protocol" to "vless", "transport" to "ws", "security" to "reality", "category" to "invalid_settings", "server" to "192.0.2.1")
        val safe = requireNotNull(filter.run(PostHogEvent("connection_result", "session", listed.toMutableMap())))
        assertEquals("vless", safe.properties?.get("protocol"))
        assertEquals("reality", safe.properties?.get("security"))
        assertEquals("invalid_settings", safe.properties?.get("category"))
        assertNull(safe.properties?.get("server"))
        for ((key, value) in listOf("protocol" to "my-server", "transport" to "private", "security" to "x", "category" to "message text", "protocol" to 5)) {
            assertNull(key, filter.run(PostHogEvent("connection_result", "session", (base + (key to value)).toMutableMap())))
        }
    }

    @Test
    fun importOutcomesCarryBucketsAndListedNamesOnly() {
        val filter = postHogConfig("phc_example", "https://example.com", analyticsCache, telemetryHttpClient { true }) { true }.beforeSendList.single()
        val listed = mapOf<String, Any>(
            "format" to "clash",
            "accepted" to "10-99",
            "refused" to "1-9",
            "refused_protocols" to "hysteria2,unknown",
            "dropped_sections" to "dns,rules",
        )
        val safe = requireNotNull(filter.run(PostHogEvent("subscription_import", "session", listed.toMutableMap())))
        assertEquals("hysteria2,unknown", safe.properties?.get("refused_protocols"))
        assertEquals("dns,rules", safe.properties?.get("dropped_sections"))
        for ((key, value) in listOf("format" to "provider-yaml", "accepted" to "57", "refused_protocols" to "vless,provider-secret", "dropped_sections" to "dns,custom")) {
            assertNull(key, filter.run(PostHogEvent("subscription_import", "session", (listed + (key to value)).toMutableMap())))
        }
        assertEquals(listOf("0", "1-9", "1-9", "10-99", "100+"), listOf(0, 1, 9, 10, 100).map(::countBucket))
    }

    @Test
    fun failuresAreSortedIntoFixedKindsOnTheDevice() {
        assertEquals(
            FailureCategory.INVALID_SETTINGS,
            failureCategory(Exception("create service: initialize outbound[0]: invalid AmneziaWG jc: at most 128 junk packets")),
        )
        assertEquals(FailureCategory.PORT_IN_USE, failureCategory(Exception("listen tcp 127.0.0.1:2080: bind: address already in use")))
        assertEquals(FailureCategory.TLS, failureCategory(Exception("outer", Exception("x509: certificate signed by unknown authority"))))
        assertEquals(FailureCategory.OTHER, failureCategory(Exception("something else")))
    }

    @Test
    fun coreCrashesKeepTheGoStackWithoutValues() {
        val dump = listOf(
            "panic: runtime error: index out of range [5] with length 3",
            "",
            "goroutine 42 [running]:",
            "github.com/amnezia-vpn/amneziawg-go/v3/device.(*Device).RoutineReadFromTUN(0xc0001a2000)",
            "\t/root/go/pkg/mod/github.com/amnezia-vpn/amneziawg-go/v3@v3.1/device/send.go:345 +0x1a5",
            "created by github.com/amnezia-vpn/amneziawg-go/v3/device.NewDevice in goroutine 1",
            "\t/root/go/pkg/mod/github.com/amnezia-vpn/amneziawg-go/v3@v3.1/device/device.go:400 +0x3c",
        ).joinToString("\n")
        val exception = requireNotNull(corePanicEvent(dump)).exceptions!!.single()
        assertEquals("runtime error: index out of range", exception.type)
        val frames = exception.stacktrace!!.frames!!
        assertEquals(listOf("NewDevice", "(*Device).RoutineReadFromTUN"), frames.map { it.function })
        assertEquals("github.com/amnezia-vpn/amneziawg-go/v3/device", frames.last().module)
        assertEquals(listOf("device.go", "send.go"), frames.map { it.filename })
        assertEquals(listOf(400, 345), frames.map { it.lineno })
        val sanitized = requireNotNull(sanitizedCrashEvent(requireNotNull(corePanicEvent(dump))))
        assertEquals("go", sanitized.platform)
        assertEquals("core_panic", sanitized.getTag("operation"))
        assertTrue(sanitized.exceptions!!.single().stacktrace!!.frames!!.none { "0x" in it.function.orEmpty() })
        // A panic with an arbitrary value keeps only its kind.
        assertEquals("panic", corePanicEvent(dump.replace("runtime error: index out of range [5] with length 3", "private value"))!!.exceptions!!.single().type)
        assertNull(corePanicEvent("no crash here"))
    }

    @Test
    fun failureReportsAreLimitedToOnePerMinuteForEachOperation() {
        assertTrue(diagnosticDue(null, 0))
        assertFalse(diagnosticDue(1_000, 60_999))
        assertTrue(diagnosticDue(1_000, 61_000))
        assertFalse(diagnosticDue(61_000, 61_000))
        assertFalse(diagnosticDue(1_000, 999))
    }

    @Test
    fun aConnectionAttemptReportsOnlyItsFirstOutcome() {
        for (first in listOf(ConnectionOutcome.PROCESS_EXIT, ConnectionOutcome.START_ERROR, ConnectionOutcome.CONNECTED)) {
            val reports = mutableListOf<Pair<ConnectionOutcome, Long>>()
            val attempt = ConnectionAttempt { reports += it.outcome to it.durationMillis }
            ShadowSystemClock.advanceBy(Duration.ofMillis(25))
            attempt.complete(first)
            ShadowSystemClock.advanceBy(Duration.ofMillis(25))
            ConnectionOutcome.entries.forEach(attempt::complete)
            assertEquals(listOf(first to 25L), reports)
        }
    }

    @Test
    fun separateAttemptsRetainRepeatedOutcomesWithinOneMinute() {
        val reports = mutableListOf<ConnectionOutcome>()
        repeat(3) {
            ConnectionAttempt { reports += it.outcome }.complete(ConnectionOutcome.CONNECTED)
        }
        assertEquals(List(3) { ConnectionOutcome.CONNECTED }, reports)
    }

    @Test
    fun queuedDiagnosticsCannotCrossConsentGenerations() {
        val original = AtomicBoolean(true)
        assertTrue(diagnosticAllowed(original, original, true))
        assertFalse(diagnosticAllowed(original, original, false))
        assertFalse(diagnosticAllowed(original, AtomicBoolean(true), true))
        original.set(false)
        assertFalse(diagnosticAllowed(original, original, true))
    }

    @Test
    fun handledErrorsRetainOnlyTheKnownOperationAndSafeMechanism() {
        val exception = SentryException().apply {
            type = "IllegalStateException"
            value = "private endpoint"
            mechanism = Mechanism().apply {
                type = "private mechanism"
                isHandled = true
            }
        }
        val event = SentryEvent().apply {
            exceptions = listOf(exception)
            setTag("operation", "service_start")
            setTag("profile", "private profile")
        }
        val safe = requireNotNull(sanitizedCrashEvent(event))
        assertEquals("service_start", safe.getTag("operation"))
        assertNull(safe.getTag("profile"))
        val mechanism = requireNotNull(safe.exceptions?.single()?.mechanism)
        assertEquals(true, mechanism.isHandled)
        assertEquals("generic", mechanism.type)
        assertNull(safe.exceptions?.single()?.value)

        event.setTag("operation", "private operation")
        exception.mechanism?.isHandled = false
        val crash = requireNotNull(sanitizedCrashEvent(event))
        assertNull(crash.getTag("operation"))
        assertEquals(false, crash.exceptions?.single()?.mechanism?.isHandled)
        assertEquals("UncaughtExceptionHandler", crash.exceptions?.single()?.mechanism?.type)
    }

    @Test
    fun postHogCoreSendsFilteredEventsOnAndroid() {
        val enabled = AtomicBoolean(true)
        val sent = CountDownLatch(1)
        var payload = ""
        val requests = CopyOnWriteArrayList<String>()
        val client = telemetryHttpClient(enabled::get).newBuilder().addInterceptor { chain ->
            requests += "${chain.request().method} ${chain.request().url.encodedPath}"
            if (chain.request().method == "POST") {
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                payload = buffer.readUtf8()
                sent.countDown()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val sdk = setupPostHog(null, postHogConfig("phc_example", "https://example.com", analyticsCache, client, enabled::get))
        try {
            sdk.capture("app_opened", properties = mapOf("profile" to "private_payload"))
            sdk.flush()
            assertTrue("Observed requests: $requests", sent.await(10, TimeUnit.SECONDS))
            assertTrue(payload.contains("app_opened"))
            assertFalse(payload.contains("private_payload"))
        } finally {
            enabled.set(false)
            sdk.optOut()
            client.dispatcher.cancelAll()
            sdk.close()
        }
    }

    @Test
    fun disablingTelemetryBlocksHttpBeforeItReachesTheNetwork() {
        var enabled = true
        var calls = 0
        val client = telemetryHttpClient { enabled }.newBuilder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://example.com/batch/").build()
        client.newCall(request).execute().close()
        enabled = false
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
        assertEquals(1, calls)
    }

    @Test
    fun sentryDropsSensitiveFieldsAndBlocksCaptureAndTransportWhenOff() {
        var enabled = true
        val options = sentryOptions("https://public@example.com/1") { enabled }
        assertEquals(false, options.dataCollection.userInfo)
        assertFalse(options.isEnableAutoSessionTracking)
        assertFalse(options.isSendClientReports)
        assertFalse(options.isAnrEnabled)
        assertFalse(options.isEnableNdk)
        assertFalse(options.isEnableScopeSync)
        assertFalse(options.isEnableAutoActivityLifecycleTracing)
        assertFalse(options.isEnableActivityLifecycleBreadcrumbs)
        assertFalse(options.isEnablePerformanceV2)
        assertFalse(options.isEnableFramesTracking)
        assertFalse(options.isAttachScreenshot)
        assertFalse(options.isAttachViewHierarchy)
        assertFalse(options.isAttachAnrThreadDump)
        assertFalse(options.isAttachRawTombstone)
        assertNull(options.tracesSampleRate)
        assertTrue(options.integrations.single() is UncaughtExceptionHandlerIntegration)
        assertNull(options.cacheDirPath)
        val event = SentryEvent().apply {
            level = SentryLevel.FATAL
            release = "example@1.0"
            user = User().apply { email = "private@example.com" }
            addBreadcrumb(Breadcrumb().apply { message = "private breadcrumb" })
            setExtra("config", "private config")
            setTag("profile", "private profile")
            contexts["vpn"] = "private endpoint"
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "private exception message"
                    stacktrace = SentryStackTrace(
                        listOf(
                            SentryStackFrame().apply {
                                module = "example.Main"
                                function = "connect"
                                filename = "/private/path/Main.kt"
                                lineno = 42
                                vars = mapOf("password" to "private password")
                                contextLine = "private source"
                            },
                        ),
                    )
                },
            )
        }
        val safe = requireNotNull(options.beforeSend?.execute(event, Hint()))
        assertEquals(event.eventId, safe.eventId)
        assertEquals(event.release, safe.release)
        assertEquals(SentryLevel.FATAL, safe.level)
        assertNull(safe.user)
        assertTrue(safe.breadcrumbs.isNullOrEmpty())
        assertTrue(safe.extras.isNullOrEmpty())
        assertNull(safe.contexts["vpn"])
        assertNull(safe.getTag("profile"))
        val exception = requireNotNull(safe.exceptions).single()
        assertEquals("IllegalStateException", exception.type)
        assertNull(exception.value)
        val frame = requireNotNull(exception.stacktrace?.frames).single()
        assertEquals("Main.kt", frame.filename)
        assertEquals(42, frame.lineno)
        assertEquals("connect", frame.function)
        assertNull(frame.vars)
        assertNull(frame.contextLine)
        assertNull(frame.absPath)
        assertTrue(options.transportGate.isConnected)
        enabled = false
        assertNull(options.beforeSend?.execute(event, Hint()))
        assertFalse(options.transportGate.isConnected)
        assertNull(sanitizedCrashEvent(SentryEvent()))
    }

    @Test
    fun sentryInitializesWithTheAndroidGuardAndPreservesTheCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // Robolectric uses desktop java.vendor, so exercise the SDK's real Android-only guard.
        val androidFlag = Platform::class.java.getDeclaredField("isAndroid").apply { isAccessible = true }
        val wasAndroid = androidFlag.getBoolean(null)
        var delegated = false
        var report: SentryEvent? = null
        val localHandler = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        Thread.setDefaultUncaughtExceptionHandler(localHandler)
        try {
            androidFlag.setBoolean(null, true)
            assertTrue(Platform.isAndroid())
            assertThrows(IllegalArgumentException::class.java) {
                Sentry.init(SentryOptions().apply { dsn = "https://public@example.com/1" })
            }
            SentryAndroid.init(RuntimeEnvironment.getApplication()) { options ->
                sentryOptions("https://public@example.com/1", options) { true }
                val filter = requireNotNull(options.beforeSend)
                options.setBeforeSend { event, hint ->
                    filter.execute(event, hint).also { report = it }
                }
                options.setTransportFactory { _, _ -> NoOpTransport.getInstance() }
            }
            assertTrue(Sentry.isEnabled())
            val options = Sentry.getCurrentScopes().options
            assertNull(options.cacheDirPath)
            assertTrue(options.integrations.single() is UncaughtExceptionHandlerIntegration)
            requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
                .uncaughtException(Thread.currentThread(), IllegalStateException("private config", IllegalArgumentException("private cause")))
            assertTrue(delegated)
            assertNotNull(report)
            assertTrue(requireNotNull(report?.exceptions).all { it.value == null && it.mechanism?.isHandled == false })
            Sentry.close()
            assertSame(localHandler, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Sentry.close()
            androidFlag.setBoolean(null, wasAndroid)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun manifestDoesNotStartSentryBeforeConsentLoads() {
        val context = RuntimeEnvironment.getApplication()
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().map { it.name }
        assertFalse(providers.any { it.startsWith("io.sentry.") })
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun filterFailuresDropEventsInsteadOfSendingUnfilteredData() {
        val failingConsent = { throw IllegalStateException("preference unavailable") }
        val analytics = postHogConfig("phc_example", "https://us.i.posthog.com", analyticsCache, telemetryHttpClient { false }, failingConsent)
        assertNull(analytics.beforeSendList.single().run(PostHogEvent("app_opened", "random-session")))
        val options = sentryOptions("https://public@example.com/1", allowed = failingConsent)
        assertNull(options.beforeSend?.execute(SentryEvent(), Hint()))
    }

    @Test
    fun endpointsRequireHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("phc_example", "http://example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("phc_example", "https://user:password@example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
        assertThrows(IllegalArgumentException::class.java) { sentryOptions("http://public@example.com/1") { true } }
        assertThrows(IllegalArgumentException::class.java) {
            postHogConfig("../other", "https://example.com", analyticsCache, telemetryHttpClient { true }) { true }
        }
    }

    @Test
    fun analyticsReusesExecutorsButNotPendingReportsOrIdentity() {
        val offlineAllowed = AtomicBoolean(true)
        val attempted = CountDownLatch(1)
        val offlineClient = telemetryHttpClient(offlineAllowed::get).newBuilder().addInterceptor { chain ->
            if (chain.request().method == "POST") {
                attempted.countDown()
                throw IOException("Offline")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val firstConfig = postHogConfig("phc_example", "https://example.com", analyticsCache, offlineClient, offlineAllowed::get)
        val sdk = setupPostHog(null, firstConfig)
        try {
            val identities = mutableSetOf(sdk.distinctId())
            assertFalse(identities.single().isBlank())
            sdk.capture("app_opened")
            sdk.flush()
            assertTrue(attempted.await(10, TimeUnit.SECONDS))
            val pending = File(requireNotNull(firstConfig.storagePrefix)).walkTopDown().filter { it.extension == "event" }.toList()
            assertTrue("An offline report must remain queued", pending.isNotEmpty())
            offlineAllowed.set(false)
            sdk.optOut()
            offlineClient.dispatcher.cancelAll()
            sdk.close()

            fun sdkThreads() = Thread.getAllStackTraces().keys.filter { it.name.startsWith("PostHog") }.toSet()
            val initialThreads = sdkThreads()
            repeat(3) {
                val allowed = AtomicBoolean(true)
                val received = CopyOnWriteArrayList<String>()
                val sent = CountDownLatch(1)
                val client = telemetryHttpClient(allowed::get).newBuilder().addInterceptor { chain ->
                    if (chain.request().method == "POST") {
                        val buffer = Buffer()
                        chain.request().body?.writeTo(buffer)
                        val batch = JsonParser.parseString(buffer.readUtf8()).asJsonObject.getAsJsonArray("batch")
                        batch.forEach { event -> received += event.asJsonObject.get("distinct_id").asString }
                        sent.countDown()
                    }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("{}".toResponseBody()).build()
                }.build()
                try {
                    val config = postHogConfig("phc_example", "https://example.com", analyticsCache, client, allowed::get).apply { flushAt = 1 }
                    assertSame(sdk, setupPostHog(sdk, config))
                    assertFalse(sdk.isOptOut())
                    val identity = sdk.distinctId()
                    assertTrue("Every reporting session must have a fresh identity", identities.add(identity))
                    assertNotEquals(firstConfig.storagePrefix, config.storagePrefix)
                    sdk.flush()
                    sdk.capture("app_opened")
                    assertTrue(sent.await(10, TimeUnit.SECONDS))
                    assertEquals(listOf(identity), received.toList())
                    assertTrue("Old queued reports must not be consumed by the new session", pending.all { it.exists() })
                } finally {
                    allowed.set(false)
                    sdk.optOut()
                    client.dispatcher.cancelAll()
                    sdk.close()
                }
                assertEquals("Repeated opt-in must not accumulate SDK executors", initialThreads, sdkThreads())
            }
        } finally {
            offlineAllowed.set(false)
            offlineClient.dispatcher.cancelAll()
            sdk.close()
        }
    }
}
