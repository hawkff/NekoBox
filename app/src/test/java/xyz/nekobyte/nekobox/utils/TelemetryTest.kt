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
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.preference.PublicDatabase
import xyz.nekobyte.nekobox.database.preference.RoomPreferenceDataStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class TelemetryTest {
    private val analyticsCache get() = RuntimeEnvironment.getApplication().cacheDir.resolve("telemetry-posthog")

    @Test
    fun switchDefaultsOnAndPersistsOptOutAcrossInflation() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, PublicDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomPreferenceDataStore(db.keyValuePairDao())
            fun preference(): SwitchPreference {
                val manager = PreferenceManager(context).apply { preferenceDataStore = store }
                val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
                return requireNotNull(screen.findPreference(Key.HELP_TO_IMPROVE))
            }
            assertTrue(preference().isChecked)
            preference().isChecked = false
            assertFalse(store.getBoolean(Key.HELP_TO_IMPROVE, true))
            assertFalse(preference().isChecked)
            preference().isChecked = true
            assertTrue(preference().isChecked)
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
                .uncaughtException(Thread.currentThread(), IllegalStateException("private config"))
            assertTrue(delegated)
            assertNotNull(report)
            assertTrue(requireNotNull(report?.exceptions).all { it.value == null })
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
