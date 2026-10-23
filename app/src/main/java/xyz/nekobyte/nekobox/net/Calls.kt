package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

// Leaving a check cancels the request in flight; a blocking execute() would run on.
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
            }
        },
    )
}

/** The body of a successful GET. Reading it blocks, so call this off the main thread. */
internal suspend fun OkHttpClient.text(url: String): String = newCall(Request.Builder().url(url).build()).await().use { response ->
    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
    response.body.string()
}
