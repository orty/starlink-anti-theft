package dev.starlinkguard.alert

import android.util.Log
import dev.starlinkguard.core.alert.TheftEvent
import dev.starlinkguard.core.alert.WebhookPayload
import dev.starlinkguard.net.NetworkProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POSTs a JSON description of an event to the user's endpoint.
 *
 * Built deliberately as a *separate* client from the dish client: the dish client is pinned to
 * cleartext HTTP/2 and bound to Wi-Fi, neither of which is right here. A webhook fired because
 * the dish was stolen usually has to go out over cellular, because the dish's Wi-Fi left with
 * it.
 */
class WebhookSender(private val networkProvider: NetworkProvider) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun send(url: String, event: TheftEvent): Result<Unit> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Result.success(Unit)

        runCatching {
            val payload = WebhookPayload.from(event)
            val body = json.encodeToString(WebhookPayload.serializer(), payload)
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("user-agent", "starlink-anti-theft/1.0")
                .build()

            client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("webhook responded HTTP ${response.code}")
                }
            }
        }.onFailure { Log.w(TAG, "webhook delivery failed", it) }
    }

    private fun client(): OkHttpClient {
        val factory = networkProvider.internetSocketFactory()
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
        if (factory != null) builder.socketFactory(factory)
        return builder.build()
    }

    private companion object {
        const val TAG = "WebhookSender"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
