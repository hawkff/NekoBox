package xyz.nekobyte.nekobox.fmt

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import libcore.BoxPlatformInterface
import libcore.Libcore
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.NativeInterface
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.net.LocalResolverImpl
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SocketProtectionTest {
    private fun icmpSocketsAllowed() = try {
        // 1 is IPPROTO_ICMP, written out for API levels whose OsConstants lack it.
        Os.close(Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 1))
        true
    } catch (_: ErrnoException) {
        false
    }

    private fun withProtection(fail: Boolean, block: (AtomicInteger) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val native = NativeInterface()
        val calls = AtomicInteger()
        val platform = object : BoxPlatformInterface by native {
            override fun autoDetectInterfaceControl(fd: Int) {
                calls.incrementAndGet()
                if (fail) throw IOException("Fixture refused socket protection")
            }
        }
        fun initialize(control: BoxPlatformInterface) {
            Libcore.initCore(
                context.packageName,
                context.cacheDir.absolutePath + "/",
                context.filesDir.absolutePath + "/",
                NekoBox.application.externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                native,
                control,
                LocalResolverImpl,
            )
        }
        initialize(platform)
        try {
            block(calls)
        } finally {
            Libcore.serveProtect(false)
            initialize(native)
        }
    }

    @Test
    fun serviceProtectionRepliesPreserveJniCallbackFailures() {
        for (fail in listOf(false, true)) {
            withProtection(fail) { calls ->
                Libcore.serveProtect(true)
                val pipe = ParcelFileDescriptor.createPipe()
                pipe[0].use { descriptor ->
                    pipe[1].use {
                        if (fail) {
                            assertThrows(Exception::class.java) { Libcore.protectSocket(descriptor.fd) }
                        } else {
                            Libcore.protectSocket(descriptor.fd)
                        }
                    }
                }
                assertTrue(calls.get() > 0)
            }
        }
    }

    @Test
    fun icmpProbeStopsAtTheFailedPlatformCallback() {
        // Some Android versions refuse unprivileged ICMP sockets to apps; the probe then fails before protection.
        assumeTrue(icmpSocketsAllowed())
        withProtection(true) { calls ->
            assertThrows(Exception::class.java) { Libcore.icmpPing("127.0.0.1", 1000) }
            assertTrue(calls.get() > 0)
        }
    }
}
