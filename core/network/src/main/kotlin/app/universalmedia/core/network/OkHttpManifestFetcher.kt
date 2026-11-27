package app.universalmedia.core.network

import app.universalmedia.media.detection.FetchResult
import app.universalmedia.media.detection.ManifestFetcher
import app.universalmedia.media.detection.ProbeResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Fetches manifests and API responses the way the in-app browser would: same
 * user agent, the page as Referer, and the browser's cookies for that URL, so
 * media the user is signed in to resolves with their own session.
 */
class OkHttpManifestFetcher(
    private val client: OkHttpClient,
    private val userAgent: () -> String? = { null },
    private val cookiesFor: (String) -> String? = { null },
    private val maxBytes: Long = 5L * 1024 * 1024,
) : ManifestFetcher {

    override suspend fun fetch(url: String, headers: Map<String, String>): FetchResult {
        val request = build(url, headers).get().build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return FetchResult.HttpError(response.code)
                val body = response.body ?: return FetchResult.Failure("Empty response")
                withContext(Dispatchers.IO) {
                    val source = body.source()
                    source.request(maxBytes + 1)
                    if (source.buffer.size > maxBytes) {
                        FetchResult.Failure("Response too large to inspect")
                    } else {
                        FetchResult.Success(
                            body = source.buffer.readUtf8(),
                            contentType = response.header("Content-Type"),
                            finalUrl = response.request.url.toString(),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            FetchResult.Failure(e.message ?: "Network error")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failure("Invalid address")
        }
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ProbeResult? {
        val request = build(url, headers).head().build()
        return try {
            client.newCall(request).await().use { response ->
                ProbeResult(
                    code = response.code,
                    contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 },
                    contentType = response.header("Content-Type"),
                )
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun build(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        userAgent()?.let { builder.header("User-Agent", it) }
        cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        return builder
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/** Suspends until the call completes; cancels the call if the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(Result.success(response))
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWith(Result.failure(e))
        }
    })
}
