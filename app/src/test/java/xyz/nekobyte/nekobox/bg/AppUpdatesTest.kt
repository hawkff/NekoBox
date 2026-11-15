package xyz.nekobyte.nekobox.bg

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppUpdatesTest {

    @Test
    fun aReleaseIsNewerOnlyWithAGreaterVersion() {
        assertTrue(isNewerVersion("2.0.7", "2.0.6"))
        assertTrue(isNewerVersion("2.1", "2.0.6"))
        assertTrue(isNewerVersion("2.0.10", "2.0.9"))
        assertTrue(isNewerVersion("v3.0.0", "2.0.6"))
        assertFalse(isNewerVersion("2.0.6", "2.0.6"))
        assertFalse(isNewerVersion("2.0.6.0", "2.0.6"))
        // An older or renamed release is no update, though its name differs.
        assertFalse(isNewerVersion("2.0.5", "2.0.6"))
        assertFalse(isNewerVersion("2.0.7 beta", "2.0.6"))
        assertFalse(isNewerVersion("", "2.0.6"))
        assertFalse(isNewerVersion("2.0.7", "pre-2.0.7"))
    }

    @Test
    fun aPreviewBuildIsBehindAnyReleaseOfAnotherName() {
        assertTrue(releaseIsNewer("2.0.5", current = "2.0.6", preview = true))
        assertFalse(releaseIsNewer("2.0.6", current = "2.0.6", preview = true))
        assertFalse(releaseIsNewer("2.0.5", current = "2.0.6", preview = false))
    }

    @Test
    fun digestNamesASha256OrNothing() {
        val hex = "258a597a369365a94f279ee2ed39b551b20ad45c4ba8a7cdd9e04c9957441cd4"
        assertEquals(hex, sha256OfDigest("sha256:$hex"))
        assertEquals(hex, sha256OfDigest("sha256:${hex.uppercase()}"))
        assertNull(sha256OfDigest(null))
        assertNull(sha256OfDigest(""))
        assertNull(sha256OfDigest("sha512:$hex"))
        assertNull(sha256OfDigest("sha256:${hex.dropLast(1)}"))
    }

    @Test
    fun theApkIsTheFirstSupportedAbisWithADigest() {
        fun asset(abi: String, digest: String? = "sha256:" + "a".repeat(64)) = JSONObject()
            .put("name", "NekoBox-2.0.7-$abi.apk")
            .put("browser_download_url", "https://example.com/$abi.apk")
            .apply { if (digest != null) put("digest", digest) }
        val release = JSONObject()
            .put("name", "2.0.7")
            .put("html_url", "https://example.com/release")
            .put("body", "\n Notes.\n")
            .put("assets", JSONArray().put(asset("x86_64")).put(asset("arm64-v8a")).put(asset("armeabi-v7a", digest = null)))

        val arm64 = parseRelease(release, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(AppRelease("2.0.7", "Notes.", "https://example.com/release", ReleaseApk("https://example.com/arm64-v8a.apk", "a".repeat(64))), arm64)
        // x86 is no prefix of x86_64.
        assertNull(parseRelease(release, listOf("x86")).apk)
        assertEquals("https://example.com/x86_64.apk", parseRelease(release, listOf("x86", "x86_64")).apk?.url)
        // An APK without a digest is refused, though the device supports it.
        assertNull(parseRelease(release, listOf("armeabi-v7a", "arm64-v8a")).apk)
    }

    @Test
    fun theBannerShowsANewerReleaseUntilDismissedForIt() {
        assertTrue(updateBannerShown("2.0.7", dismissed = "", current = "2.0.6", preview = false))
        assertFalse(updateBannerShown("2.0.7", dismissed = "2.0.7", current = "2.0.6", preview = false))
        assertTrue(updateBannerShown("2.0.8", dismissed = "2.0.7", current = "2.0.6", preview = false))
        // Installed meanwhile.
        assertFalse(updateBannerShown("2.0.7", dismissed = "", current = "2.0.7", preview = false))
        assertFalse(updateBannerShown("", dismissed = "", current = "2.0.6", preview = false))
    }

    @Test
    fun theDailyCheckFollowsTheSetting() {
        // Store builds never schedule the check.
        assumeTrue(AppUpdates.checksReleases)
        ConfigBuilderTestEnv.reset()
        val context = RuntimeEnvironment.getApplication()
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().build())
        fun work() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK_NAME).get().single()
        DataStore.configurationStore.registerChangeListener(AppUpdates)
        try {
            assertTrue(DataStore.checkUpdates)
            AppUpdates.schedule(context).result.get()
            assertEquals(WorkInfo.State.ENQUEUED, work().state)
            assertEquals(TimeUnit.DAYS.toMillis(1), work().periodicityInfo?.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)

            DataStore.checkUpdates = false
            assertEquals(WorkInfo.State.CANCELLED, work().state)
            DataStore.checkUpdates = true
            assertEquals(WorkInfo.State.ENQUEUED, work().state)
        } finally {
            DataStore.configurationStore.unregisterChangeListener(AppUpdates)
        }
    }

    @Test
    fun releaseNotesBecomePlainText() {
        val notes = plainNotes("**Subscriptions**\n- Fixes `geoip` lists, see [the docs](https://example.com).\n\n\n\n**Full Changelog**: https://example.com/compare")
        assertEquals("Subscriptions\n- Fixes geoip lists, see the docs.", notes)
        val long = (1..400).joinToString("\n") { "- line $it" }
        val cut = plainNotes(long)
        assertTrue(cut.length <= 1502 && cut.endsWith("\n…") && cut.lines().dropLast(1).all { it.startsWith("- line ") })
    }
}
