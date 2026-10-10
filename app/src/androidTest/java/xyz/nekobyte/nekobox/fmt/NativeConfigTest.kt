package xyz.nekobyte.nekobox.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.net.LocalResolverImpl

@RunWith(AndroidJUnit4::class)
class NativeConfigTest {
    @Test
    fun generatedProbeStartsWithTheBundledCore() = runBlocking(Dispatchers.IO) {
        val profile = ProxyEntity().putBean(
            SOCKSBean().apply {
                name = "native-probe"
                serverAddress = "192.0.2.1"
                serverPort = 1080
                initializeDefaultValues()
            },
        )
        val instance = Libcore.newSingBoxInstance(buildConfig(profile, forTest = true).config, LocalResolverImpl)
        try {
            instance.start()
            val version = Libcore.versionBox()
            // The exact pin lives in buildScript/lib/core/get_source_env.sh and changes on
            // every core bump; what must hold is that build.sh injected a real version.
            assertTrue(version, version.startsWith("sing-box: 1."))
            assertTrue(version, version.contains("go1.27."))
        } finally {
            instance.close()
        }
    }
}
