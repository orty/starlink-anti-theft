package dev.starlinkguard.core.grpc

import dev.starlinkguard.core.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrpcFramingTest {

    @Test
    fun `frames the get_status body exactly as the wire format specifies`() {
        val framed = GrpcFraming.frame(byteArrayOf(0xE2.toByte(), 0x3E, 0x00))
        assertEquals("00 00 00 00 03 E2 3E 00", framed.toHex())
    }

    @Test
    fun `frame and unframe round trip`() {
        val payload = ByteArray(600) { (it % 251).toByte() }
        assertArrayEquals(payload, GrpcFraming.unframeFirst(GrpcFraming.frame(payload)))
    }

    @Test
    fun `an empty body yields no message`() {
        // What a trailers-only gRPC error response looks like.
        assertNull(GrpcFraming.unframeFirst(ByteArray(0)))
    }

    @Test
    fun `a zero length frame yields an empty message rather than null`() {
        assertArrayEquals(ByteArray(0), GrpcFraming.unframeFirst(GrpcFraming.frame(ByteArray(0))))
    }

    @Test
    fun `compressed frames are reported as unimplemented`() {
        val framed = GrpcFraming.frame(byteArrayOf(1, 2, 3)).also { it[0] = 1 }
        val e = runCatching { GrpcFraming.unframeFirst(framed) }.exceptionOrNull()
        assertEquals(GrpcStatus.UNIMPLEMENTED, (e as GrpcException).status)
    }

    @Test
    fun `a truncated header is rejected`() {
        val e = runCatching { GrpcFraming.unframeFirst(byteArrayOf(0, 0, 0)) }.exceptionOrNull()
        assertEquals(GrpcStatus.INTERNAL, (e as GrpcException).status)
    }

    @Test
    fun `a frame claiming more bytes than were delivered is rejected`() {
        val e = runCatching {
            GrpcFraming.unframeFirst(byteArrayOf(0, 0, 0, 0, 10, 1, 2))
        }.exceptionOrNull()
        assertEquals(GrpcStatus.INTERNAL, (e as GrpcException).status)
    }

    @Test
    fun `status codes map from their numeric form`() {
        assertEquals(GrpcStatus.PERMISSION_DENIED, GrpcStatus.fromCode(7))
        assertEquals(GrpcStatus.UNAVAILABLE, GrpcStatus.fromCode(14))
        assertEquals(GrpcStatus.UNKNOWN, GrpcStatus.fromCode(999))
    }
}
