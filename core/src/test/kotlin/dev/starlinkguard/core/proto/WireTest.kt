package dev.starlinkguard.core.proto

import dev.starlinkguard.core.Msg
import dev.starlinkguard.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    @Test
    fun `reads single and multi byte varints`() {
        val bytes = Msg().varint(1, 0).varint(2, 127).varint(3, 128).varint(4, 300).varint(5, Long.MAX_VALUE).build()
        val reader = WireReader(bytes)
        val values = mutableListOf<Long>()
        reader.forEachField { tag, r ->
            assertEquals(WIRETYPE_VARINT, tag.wireType)
            values += r.readVarint()
            true
        }
        assertEquals(listOf(0L, 127L, 128L, 300L, Long.MAX_VALUE), values)
    }

    @Test
    fun `writer encodes the get_status request as E2 3E 00`() {
        // The canonical three-byte body: field 1004, wire type 2, zero length.
        val bytes = WireWriter().writeEmptyMessage(1004).toByteArray()
        assertEquals("E2 3E 00", bytes.toHex())
    }

    @Test
    fun `writer encodes the get_location request as CA 3F 00`() {
        val bytes = WireWriter().writeEmptyMessage(1017).toByteArray()
        assertEquals("CA 3F 00", bytes.toHex())
    }

    @Test
    fun `skips unknown fields of every wire type and still reads the one we want`() {
        val bytes = Msg()
            .varint(1, 42)
            .fixed64(2, 1234L)
            .string(3, "ignored")
            .fixed32(4, 99)
            .float(5, 7.5f)
            .build()

        var found: Float? = null
        WireReader(bytes).forEachField { tag, r ->
            if (tag.field == 5 && tag.wireType == WIRETYPE_FIXED32) {
                found = r.readFloat(); true
            } else {
                false // exercises skip() for varint, fixed64, length-delimited and fixed32
            }
        }
        assertEquals(7.5f, found!!, 0.0001f)
    }

    @Test
    fun `float and double round trip through the reader`() {
        val bytes = Msg().float(1, -123.456f).double(2, 47.6062).build()
        var f: Float? = null
        var d: Double? = null
        WireReader(bytes).forEachField { tag, r ->
            when (tag.field) {
                1 -> { f = r.readFloat(); true }
                2 -> { d = r.readDouble(); true }
                else -> false
            }
        }
        assertEquals(-123.456f, f!!, 0.0001f)
        assertEquals(47.6062, d!!, 0.0000001)
    }

    @Test
    fun `nested messages are read through a scoped reader`() {
        val bytes = Msg().message(1, Msg().float(3, 3.25f)).varint(2, 5).build()
        var tilt: Float? = null
        var trailing: Long? = null
        WireReader(bytes).forEachField { tag, r ->
            when (tag.field) {
                1 -> {
                    val inner = r.readMessage()
                    inner.forEachField { t, ir -> if (t.field == 3) { tilt = ir.readFloat(); true } else false }
                    true
                }
                2 -> { trailing = r.readVarint(); true }
                else -> false
            }
        }
        assertEquals(3.25f, tilt!!, 0.0001f)
        // Reading the nested message must not disturb the outer cursor.
        assertEquals(5L, trailing)
    }

    @Test(expected = ProtoParseException::class)
    fun `truncated varint is rejected`() {
        // 0x80 sets the continuation bit but the buffer ends.
        WireReader(byteArrayOf(0x80.toByte())).readVarint()
    }

    @Test(expected = ProtoParseException::class)
    fun `length delimited field overrunning the buffer is rejected`() {
        // field 1, wire type 2, claims 50 bytes, supplies 2
        WireReader(byteArrayOf(0x0A, 50, 1, 2)).forEachField { _, r -> r.readMessage(); true }
    }

    @Test(expected = ProtoParseException::class)
    fun `truncated fixed32 is rejected`() {
        WireReader(byteArrayOf(0x0D, 1, 2)).forEachField { _, r -> r.readFloat(); true }
    }

    @Test(expected = ProtoParseException::class)
    fun `field number zero is rejected`() {
        WireReader(byteArrayOf(0x00, 0x01)).readTag()
    }

    @Test(expected = ProtoParseException::class)
    fun `group wire types are rejected rather than mis-parsed`() {
        // field 1, wire type 3 (start group)
        WireReader(byteArrayOf(0x0B, 0x01)).forEachField { _, _ -> false }
    }

    @Test
    fun `empty buffer has no fields`() {
        val reader = WireReader(ByteArray(0))
        assertFalse(reader.hasMore)
        var visited = false
        reader.forEachField { _, _ -> visited = true; true }
        assertFalse(visited)
    }

    @Test
    fun `reader rejects out of range bounds`() {
        var threw = false
        try {
            WireReader(ByteArray(4), pos = 2, end = 9)
        } catch (e: ProtoParseException) {
            threw = true
        }
        assertTrue(threw)
    }
}
