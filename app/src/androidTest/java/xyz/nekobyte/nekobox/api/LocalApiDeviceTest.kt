package xyz.nekobyte.nekobox.api

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.ui.MainActivity
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class LocalApiDeviceTest {
    @Test
    fun loopbackAuthenticationDiscoveryAndFixtureCrud() {
        // This scenario owns a disposable emulator, not a physical install with saved profiles.
        assumeTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.startsWith("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = LocalApiAccess.read(context)
        assumeTrue(original?.enabled != true)
        val config = LocalApiAccess.fresh().copy(port = 19091)
        LocalApiAccess.write(context, config)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var groupId: Long? = null
        try {
            var ready = false
            repeat(100) {
                if (!ready) {
                    ready = runCatching { call(config, "app.status").has("package") }.getOrDefault(false)
                    if (!ready) Thread.sleep(100)
                }
            }
            assertTrue("API did not start", ready)
            assertEquals(401, exchange(config.port, "GET /v1", null, null).first)
            val catalog = call(config, "api.describe").getJSONObject("commands")
            assertTrue(catalog.has("profiles.create"))
            val group = call(config, "groups.create", JSONObject().put("name", "API device fixture"))
            groupId = group.getLong("id")
            val profile = call(
                config,
                "profiles.create",
                JSONObject().put("groupId", groupId).put("type", 0).put(
                    "configuration",
                    JSONObject()
                        .put("name", "API device profile").put("serverAddress", "192.0.2.1").put("serverPort", 1080).put("password", "fictional-secret"),
                ),
            )
            val id = profile.getLong("id")
            assertFalse(profile.has("configuration"))
            call(config, "profiles.update", JSONObject().put("id", id).put("configuration", JSONObject().put("name", "API device edited")))
            val safe = call(config, "profiles.get", JSONObject().put("id", id))
            assertEquals("API device edited", safe.getString("name"))
            assertFalse(safe.toString().contains("fictional-secret"))
            val full = call(config, "profiles.get", JSONObject().put("id", id).put("includeSecrets", true))
            assertEquals("fictional-secret", full.getJSONObject("configuration").getString("password"))
            val rejected = request(config, "profiles.delete", JSONObject().put("id", id).put("confirm", false))
            assertEquals("confirmation_required", rejected.getJSONObject("error").getString("code"))
            call(config, "profiles.get", JSONObject().put("id", id))
            call(config, "profiles.delete", JSONObject().put("id", id).put("confirm", true))
            assertEquals(0, call(config, "profiles.list", JSONObject().put("groupId", groupId)).getInt("total"))
        } finally {
            try {
                groupId?.let { call(config, "groups.delete", JSONObject().put("id", it).put("confirm", true)) }
            } finally {
                LocalApiAccess.write(context, original ?: config.copy(enabled = false))
                context.stopService(Intent(context, LocalApiService::class.java))
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun call(config: LocalApiAccess.Config, operation: String, parameters: JSONObject = JSONObject()): JSONObject {
        val response = request(config, operation, parameters)
        assertFalse("$operation: $response", response.has("error"))
        return response.getJSONObject("result")
    }

    private fun request(config: LocalApiAccess.Config, operation: String, parameters: JSONObject): JSONObject {
        val (status, body) = exchange(config.port, "POST /v1/$operation", config.token, parameters.toString())
        assertEquals(200, status)
        return JSONObject(body)
    }

    /**
     * One HTTP/1.0 exchange over a plain socket. The test runs in the app's process, whose network security policy
     * refuses cleartext to every host; a socket is outside that policy, as the API's clients in other apps are.
     */
    private fun exchange(port: Int, request: String, token: String?, body: String?): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val payload = body.orEmpty().toByteArray()
            val head = buildString {
                append("$request HTTP/1.0\r\nHost: 127.0.0.1:$port\r\n")
                token?.let { append("Authorization: Bearer $it\r\n") }
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray() + payload) }.flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            return response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
        }
    }
}
