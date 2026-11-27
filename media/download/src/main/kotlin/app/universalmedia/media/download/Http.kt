package app.universalmedia.media.download

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** A download failure with a user-facing message. Retryable failures are retried automatically. */
class DownloadException(message: String, val retryable: Boolean = false) : Exception(message)

internal fun httpError(code: Int): DownloadException = when (code) {
    401, 403 -> DownloadException("The site refused access (HTTP $code). Sign in on the site, then retry.")
    404, 410 -> DownloadException("The file is no longer available (HTTP $code).")
    408, 429 -> DownloadException("The server is busy (HTTP $code).", retryable = true)
    in 500..599 -> DownloadException("Server error (HTTP $code).", retryable = true)
    else -> DownloadException("The server refused the download (HTTP $code).")
}

/** Requests made the way the in-app browser makes them: same UA, cookies and referer. */
internal class Http(private val env: DownloadEnvironment, private val referer: String?) {

    fun request(url: String, range: String? = null): Request {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw DownloadException("Invalid address")
        }
        env.userAgent()?.let { builder.header("User-Agent", it) }
        env.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        referer?.let { builder.header("Referer", it) }
        range?.let { builder.header("Range", it) }
        return builder.build()
    }

    suspend fun open(request: Request): Response = env.client.newCall(request).await()

    suspend fun bytes(url: String, range: String? = null): ByteArray = open(request(url, range)).use { r ->
        if (!r.isSuccessful) throw httpError(r.code)
        withContext(Dispatchers.IO) { r.body?.bytes() ?: throw DownloadException("Empty response", retryable = true) }
    }

    /** Body text and the final URL after redirects. */
    suspend fun text(url: String): Pair<String, String> = open(request(url)).use { r ->
        if (!r.isSuccessful) throw httpError(r.code)
        val body = withContext(Dispatchers.IO) { r.body?.string() } ?: throw DownloadException("Empty response", retryable = true)
        body to r.request.url.toString()
    }
}

/** Runs [block] up to [attempts] times for network hiccups and retryable server errors. */
internal suspend fun <T> retrying(attempts: Int = 3, block: suspend () -> T): T {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: DownloadException) {
            if (!e.retryable) throw e
            last = e
        } catch (e: IOException) {
            last = e
        }
        delay(1_000L * (attempt + 1))
    }
    throw last ?: DownloadException("Download failed", retryable = true)
}

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
