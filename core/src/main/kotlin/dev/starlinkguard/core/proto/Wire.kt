package dev.starlinkguard.core.proto

/**
 * A hand-rolled, allocation-light protobuf wire-format reader and writer.
 *
 * The app talks to exactly two Starlink RPCs and reads about ten fields out of the replies,
 * so generated stubs would be a lot of build machinery for very little gain. More
 * importantly, a reader that skips unknown fields by wire type is *more* tolerant of dish
 * firmware changes than regenerated stubs: SpaceX adds and removes fields regularly, and
 * anything this app does not care about is stepped over without complaint.
 *
 * See [dev.starlinkguard.core.starlink.StarlinkFields] for the field numbers themselves.
 */

class ProtoParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val WIRETYPE_VARINT = 0
const val WIRETYPE_FIXED64 = 1
const val WIRETYPE_LENGTH_DELIMITED = 2
const val WIRETYPE_START_GROUP = 3
const val WIRETYPE_END_GROUP = 4
const val WIRETYPE_FIXED32 = 5

/** A decoded protobuf key: the field number and its wire type. */
data class Tag(val field: Int, val wireType: Int)

/**
 * Reads protobuf-encoded bytes out of [buf] between [pos] and [end].
 *
 * Sub-messages are read through [readMessage], which returns a reader scoped to the nested
 * bytes, so nesting never requires copying the backing array.
 */
class WireReader(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val end: Int = buf.size,
) {
    init {
        if (pos < 0 || end > buf.size || pos > end) {
            throw ProtoParseException("reader bounds [$pos, $end) outside buffer of ${buf.size}")
        }
    }

    val hasMore: Boolean get() = pos < end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw ProtoParseException("truncated varint")
            if (shift >= 64) throw ProtoParseException("varint longer than 10 bytes")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readTag(): Tag {
        val key = readVarint()
        val field = (key ushr 3).toInt()
        val wireType = (key and 0x7L).toInt()
        if (field == 0) throw ProtoParseException("field number 0 is not valid")
        return Tag(field, wireType)
    }

    fun readFixed32(): Int {
        if (pos + 4 > end) throw ProtoParseException("truncated fixed32")
        var v = 0
        for (i in 0 until 4) {
            v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        }
        pos += 4
        return v
    }

    fun readFixed64(): Long {
        if (pos + 8 > end) throw ProtoParseException("truncated fixed64")
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += 8
        return v
    }

    fun readFloat(): Float = Float.fromBits(readFixed32())

    fun readDouble(): Double = Double.fromBits(readFixed64())

    fun readBool(): Boolean = readVarint() != 0L

    fun readInt32(): Int = readVarint().toInt()

    /** Returns a reader scoped to the next length-delimited chunk. */
    fun readMessage(): WireReader {
        val len = readVarint()
        if (len < 0 || len > (end - pos)) throw ProtoParseException("length-delimited field overruns buffer")
        val start = pos
        pos += len.toInt()
        return WireReader(buf, start, pos)
    }

    fun readBytes(): ByteArray {
        val len = readVarint()
        if (len < 0 || len > (end - pos)) throw ProtoParseException("bytes field overruns buffer")
        val out = buf.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** Steps over a field the caller does not care about. */
    fun skip(wireType: Int) {
        when (wireType) {
            WIRETYPE_VARINT -> readVarint()
            WIRETYPE_FIXED64 -> {
                if (pos + 8 > end) throw ProtoParseException("truncated fixed64")
                pos += 8
            }
            WIRETYPE_LENGTH_DELIMITED -> {
                val len = readVarint()
                if (len < 0 || len > (end - pos)) throw ProtoParseException("length-delimited field overruns buffer")
                pos += len.toInt()
            }
            WIRETYPE_FIXED32 -> {
                if (pos + 4 > end) throw ProtoParseException("truncated fixed32")
                pos += 4
            }
            // Groups were removed from proto3 and the dish never emits them. Failing loudly
            // beats silently mis-parsing the rest of the message.
            WIRETYPE_START_GROUP, WIRETYPE_END_GROUP ->
                throw ProtoParseException("group wire types are not supported")
            else -> throw ProtoParseException("unknown wire type $wireType")
        }
    }

    /**
     * Walks every field in this message, handing each one to [onField].
     *
     * The callback must consume exactly the field it was given; returning `false` means
     * "not interested", and the field is skipped for you.
     */
    inline fun forEachField(onField: (Tag, WireReader) -> Boolean) {
        while (hasMore) {
            val tag = readTag()
            if (!onField(tag, this)) skip(tag.wireType)
        }
    }
}

/** Builds protobuf messages. Only the handful of shapes the dish requests need. */
class WireWriter {
    private val out = ArrayList<Byte>(16)

    fun writeVarint(value: Long): WireWriter {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                return this
            }
            out.add((b or 0x80).toByte())
        }
    }

    fun writeTag(field: Int, wireType: Int): WireWriter =
        writeVarint(((field.toLong() shl 3) or wireType.toLong()))

    fun writeBool(field: Int, value: Boolean): WireWriter {
        writeTag(field, WIRETYPE_VARINT)
        return writeVarint(if (value) 1L else 0L)
    }

    fun writeMessage(field: Int, body: ByteArray): WireWriter {
        writeTag(field, WIRETYPE_LENGTH_DELIMITED)
        writeVarint(body.size.toLong())
        body.forEach { out.add(it) }
        return this
    }

    /** Writes a nested message with no fields set — the shape every Starlink request uses. */
    fun writeEmptyMessage(field: Int): WireWriter = writeMessage(field, ByteArray(0))

    fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
}
