package dev.starlinkguard.core.grpc

import dev.starlinkguard.core.Fixtures
import dev.starlinkguard.core.toHex
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real transport against MockWebServer running in `H2_PRIOR_KNOWLEDGE` mode —
 * the same cleartext HTTP/2 the dish serves — so the framing, headers, path and trailer
 * handling are all covered without a dish present.
 */
class OkHttpDishClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpDishClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.start()
        client = OkHttpDishClient.create(host = server.hostName, port = server.port, timeoutMillis = 5_000)
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun grpcResponse(body: ByteArray, status: Int = 0): MockResponse =
        MockResponse()
            .setHeader("content-type", "application/grpc+proto")
            .setBody(Buffer().write(GrpcFraming.frame(body)))
            .setTrailers(Headers.headersOf("grpc-status", status.toString()))

    @Test
    fun `status posts the right request and parses the reply`() = runBlocking {
        server.enqueue(grpcResponse(Fixtures.statusResponse()))

        val status = client.status()

        assertEquals(12.5f, status.azimuthDeg!!, 0.001f)
        assertEquals(62.0f, status.elevationDeg!!, 0.001f)
        assertEquals(3.25f, status.tiltDeg!!, 0.001f)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/SpaceX.API.Device.Device/Handle", recorded.path)
        assertEquals("application/grpc+proto", recorded.getHeader("content-type"))
        assertEquals("trailers", recorded.getHeader("te"))
        assertEquals("00 00 00 00 03 E2 3E 00", recorded.body.readByteArray().toHex())
    }

    @Test
    fun `location parses a fix`() = runBlocking {
        server.enqueue(grpcResponse(Fixtures.locationResponse()))

        val location = client.location()!!

        assertEquals(47.6062, location.latitude, 0.000001)
        assertEquals(-122.3321, location.longitude, 0.000001)
        assertEquals("00 00 00 00 03 CA 3F 00", server.takeRequest().body.readByteArray().toHex())
    }

    @Test
    fun `location returns null when the dish denies permission`() = runBlocking {
        // The usual reply unless the owner enables location sharing in the Starlink app.
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/grpc+proto")
                .setHeader("grpc-status", "7")
                .setHeader("grpc-message", "not authorized"),
        )

        assertNull(client.location())
    }

    @Test
    fun `location returns null when the rpc is unimplemented`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/grpc+proto")
                .setHeader("grpc-status", "12"),
        )

        assertNull(client.location())
    }

    @Test
    fun `a non ok status on the status rpc is surfaced`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/grpc+proto")
                .setBody(Buffer())
                .setTrailers(Headers.headersOf("grpc-status", "14", "grpc-message", "dish rebooting")),
        )

        val e = runCatching { client.status() }.exceptionOrNull()
        assertEquals(GrpcStatus.UNAVAILABLE, (e as GrpcException).status)
    }

    @Test
    fun `an http error is reported as unavailable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))

        val e = runCatching { client.status() }.exceptionOrNull()
        assertEquals(GrpcStatus.UNAVAILABLE, (e as GrpcException).status)
    }
}
