package xyz.nekobyte.nekobox.utils

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.BuildConfig

@RunWith(AndroidJUnit4::class)
class TelemetryDeliveryDeviceTest {
    private class DeliveryProbeException : IllegalStateException("private probe data must be removed")

    @Test
    fun emitConfiguredDeliveryProbes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("telemetryDelivery") == "true")
        assertTrue(BuildConfig.POSTHOG_PROJECT_TOKEN.isNotBlank())
        assertTrue(BuildConfig.SENTRY_DSN.isNotBlank())
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!Sentry.isEnabled() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Sentry initialization did not complete", Sentry.isEnabled())

        repeat(3) { Telemetry.connectionResult(ConnectionOutcome.CONNECTED, 271L + it) }
        Telemetry.connectionInterrupted()
        var eventId = SentryId.EMPTY_ID
        Sentry.withScope { scope ->
            scope.setTag("operation", DiagnosticOperation.SERVICE_START.name.lowercase())
            eventId = Sentry.captureException(DeliveryProbeException())
        }
        assertNotEquals(SentryId.EMPTY_ID, eventId)
        Sentry.flush(10_000)
        // PostHog drains asynchronously; instrumentation completion can terminate this process.
        Thread.sleep(2_000)
        println("TELEMETRY_SENTRY_EVENT_ID=$eventId")
    }
}
