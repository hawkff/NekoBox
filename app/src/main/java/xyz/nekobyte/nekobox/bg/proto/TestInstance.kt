package xyz.nekobyte.nekobox.bg.proto

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.net.LocalResolverImpl

// Login over DERP plus exit node selection; a phone on a slow network needs several seconds.
const val TAILSCALE_READY_TIMEOUT_MS = 20_000

// Short JNI rounds bound cancellation latency without closing a box while it is initializing.
internal suspend fun awaitTailscaleReady(box: libcore.BoxInstance, tag: String, waitForExitNode: Boolean) {
    val deadline = SystemClock.elapsedRealtime() + TAILSCALE_READY_TIMEOUT_MS
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            Libcore.tailscaleWaitReady(box, tag, waitForExitNode, 1_000)
            return
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline || e.readableMessage.contains("Tailscale needs login:")) throw e
        }
        delay(50)
    }
}

class TestInstance(
    profile: ProxyEntity,
    val link: String,
    private val timeout: Int,
    private val preparedConfig: ConfigBuildResult? = null,
    // A page to read through the profile once the test passes, such as one that reports the caller's address.
    private val answerUrl: String? = null,
) : BoxInstance(profile) {

    /** What [answerUrl] said, after [doTest]; null when it was not asked or could not be read. */
    var answer: String? = null
        private set

    suspend fun doTest(): Int = runProbe {
        for (endpoint in config.tailscaleEndpoints.values) {
            awaitTailscaleReady(box, endpoint.tag, endpoint.waitForExitNode)
        }
        currentCoroutineContext().ensureActive()
        Libcore.urlTest(box, link, timeout).also {
            // The test's connection resolved the server name, so the answer comes from the core's cache.
            lookUpServer()
            // A page that cannot be read leaves the answer unknown; the test itself passed.
            answerUrl?.let { url -> answer = runCatching { Libcore.fetchText(box, url, timeout) }.getOrNull() }
        }
    }

    // A lookup alone starts the core without the sidecar processes a URL test needs.
    private var lookupOnly = false

    /** What the profile's server name stands for, through this core's resolver for the profile. */
    suspend fun resolveServer(): String? {
        lookupOnly = true
        return runProbe { lookUpServer() }
    }

    override fun launch() {
        if (lookupOnly) box.start() else super.launch()
    }

    override fun buildConfig() {
        config = preparedConfig ?: buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(safeConfigDiagnostics(config, pluginConfigs.size))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
