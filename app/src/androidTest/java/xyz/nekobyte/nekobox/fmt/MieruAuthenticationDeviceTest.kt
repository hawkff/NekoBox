package xyz.nekobyte.nekobox.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean
import xyz.nekobyte.nekobox.fmt.mieru.buildMieruConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class MieruAuthenticationDeviceTest {
    @Test
    fun bundledClientRejectsMissingAndWrongCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executable = File(context.applicationInfo.nativeLibraryDir, "libmieru.so")
        assertTrue(executable.canExecute())
        val port = ServerSocket(0).use { it.localPort }
        val config = File.createTempFile("mieru-auth-", ".json", context.cacheDir)
        val output = File.createTempFile("mieru-output-", ".log", context.cacheDir)
        val bean = MieruBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 9
            username = "remote-fixture"
            password = "remote-fixture"
            initializeDefaultValues()
        }
        config.writeText(bean.buildMieruConfig(port, "local-fixture", "local-password"))
        var process: Process? = null
        try {
            process = ProcessBuilder(executable.absolutePath, "run").apply {
                environment()["MIERU_CONFIG_JSON_FILE"] = config.absolutePath
                environment().remove("MIERU_PROTECT_PATH")
                redirectErrorStream(true)
                redirectOutput(output)
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val ready = runCatching { connect(port).use { } }.isSuccess
                if (ready) break
                check(process.isAlive && System.nanoTime() < deadline) { "Mieru listener did not start" }
                Thread.sleep(50)
            }
            connect(port).use { socket ->
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read())
                assertEquals(255, socket.getInputStream().read())
            }
            for ((methods, password) in listOf(
                byteArrayOf(2) to "local-password",
                byteArrayOf(0, 2) to "local-password",
                byteArrayOf(2, 0) to "local-password",
                byteArrayOf(0, 2) to "wrong-password",
            )) {
                connect(port).use { socket ->
                    val input = socket.getInputStream()
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(5, methods.size.toByte()) + methods)
                    assertEquals(5, input.read())
                    assertEquals(2, input.read())
                    val user = "local-fixture".toByteArray()
                    val secret = password.toByteArray()
                    out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(secret.size.toByte()) + secret)
                    assertEquals(1, input.read())
                    val status = input.read()
                    if (password == "local-password") assertEquals(0, status) else assertTrue(status > 0)
                }
            }
        } finally {
            process?.let {
                it.destroy()
                if (!it.waitFor(2, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    check(it.waitFor(2, TimeUnit.SECONDS)) { "Mieru process did not stop" }
                }
            }
            config.delete()
            output.delete()
        }
    }

    private fun connect(port: Int) = Socket().apply {
        try {
            soTimeout = 3_000
            connect(InetSocketAddress("127.0.0.1", port), 100)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }
}
