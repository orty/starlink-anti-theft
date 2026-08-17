package dev.starlinkguard.core.grpc

import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import dev.starlinkguard.core.starlink.StarlinkCodec
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A [DishClient] that speaks gRPC over cleartext HTTP/2 to the dish at 192.168.100.1:9200.
 *
 * The dish serves native gRPC over h2c with prior knowledge — no TLS, no ALPN, no upgrade
 * handshake, and no authentication for the status RPCs. OkHttp handles that directly with
 * [Protocol.H2_PRIOR_KNOWLEDGE], so the whole client is a POST with a length-prefixed body.
 *
 * @param socketFactory on Android, pass the Wi-Fi [android.net.Network]'s socket factory.
 *   Without it the OS happily routes the request out over cellular the moment it decides the
 *   dish's LAN has no internet, and every poll fails with a timeout.
 */
class OkHttpDishClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
) : DishClient {

    override suspend fun status(): DishStatus = withContext(Dispatchers.IO) {
        val body = call(StarlinkCodec.encodeGetStatusRequest())
            ?: throw GrpcException(GrpcStatus.INTERNAL, "dish returned an empty status response")
        StarlinkCodec.decodeStatusResponse(body)
    }

    override suspend fun location(): DishLocation? = withContext(Dispatchers.IO) {
        val body = try {
            call(StarlinkCodec.encodeGetLocationRequest())
        } catch (e: GrpcException) {
            // The dish refuses this RPC unless location sharing is enabled in the Starlink
            // app, and some service plans never expose it. That is an expected configuration,
            // not a failure worth propagating.
            if (e.status == GrpcStatus.PERMISSION_DENIED ||
                e.status == GrpcStatus.UNIMPLEMENTED ||
                e.status == GrpcStatus.UNAUTHENTICATED
            ) {
                return@withContext null
            }
            throw e
        } ?: return@withContext null
        StarlinkCodec.decodeLocationResponse(body)
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun call(message: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(HANDLE_PATH).build())
            .header("te", "trailers")
            .header("grpc-accept-encoding", "identity")
            .post(GrpcFraming.frame(message).toRequestBody(GRPC_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GrpcException(
                    GrpcStatus.UNAVAILABLE,
                    "dish replied HTTP ${response.code}",
                )
            }

            // The body must be fully read before trailers become available.
            val raw = try {
                response.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                throw GrpcException(GrpcStatus.UNAVAILABLE, "reading dish response failed", e)
            }

            val trailers = try {
                response.trailers()
            } catch (e: IOException) {
                null
            }

            val statusCode = (trailers?.get(GRPC_STATUS) ?: response.header(GRPC_STATUS))
                ?.toIntOrNull()
            if (statusCode != null && statusCode != GrpcStatus.OK.code) {
                val detail = trailers?.get(GRPC_MESSAGE)
                    ?: response.header(GRPC_MESSAGE)
                    ?: "no detail"
                throw GrpcException(GrpcStatus.fromCode(statusCode), detail)
            }

            return GrpcFraming.unframeFirst(raw)
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.100.1"
        const val DEFAULT_PORT = 9200
        private const val HANDLE_PATH = "SpaceX.API.Device.Device/Handle"
        private const val GRPC_STATUS = "grpc-status"
        private const val GRPC_MESSAGE = "grpc-message"
        private val GRPC_MEDIA_TYPE = "application/grpc+proto".toMediaType()

        /**
         * Builds a client pointed at a dish.
         *
         * Timeouts are deliberately short: a poll that has not answered in a few seconds is
         * more useful reported as unreachable than left hanging until the next poll.
         */
        fun create(
            host: String = DEFAULT_HOST,
            port: Int = DEFAULT_PORT,
            socketFactory: SocketFactory? = null,
            timeoutMillis: Long = 5_000,
        ): OkHttpDishClient {
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .apply { if (socketFactory != null) socketFactory(socketFactory) }
                .build()
            return OkHttpDishClient("http://$host:$port/".toHttpUrl(), client)
        }
    }
}
