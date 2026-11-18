package com.tgmonitor.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    class Ok(val html: String, val finalUrl: String) : FetchResult
    class HttpError(val code: Int, val retryAfterSeconds: Long? = null) : FetchResult
    class NetworkError(val message: String) : FetchResult
}

interface PageFetcher {
    suspend fun fetch(url: String): FetchResult
}

class OkHttpPageFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : PageFetcher {

    override suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FetchResult.HttpError(response.code, response.header("Retry-After")?.toLongOrNull())
                } else {
                    FetchResult.Ok(response.body?.string().orEmpty(), response.request.url.toString())
                }
            }
        } catch (e: IOException) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
