package dev.starlinkguard.core.grpc

/**
 * gRPC's length-prefixed message framing.
 *
 * Each message on the wire is a one-byte compression flag followed by a big-endian uint32
 * length and then the protobuf payload. Unary calls send and receive exactly one frame, which
 * is why this app can speak gRPC over a plain HTTP/2 client instead of pulling in grpc-java.
 */
object GrpcFraming {

    private const val HEADER_SIZE = 5

    fun frame(message: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + message.size)
        out[0] = 0 // not compressed
        out[1] = (message.size ushr 24 and 0xFF).toByte()
        out[2] = (message.size ushr 16 and 0xFF).toByte()
        out[3] = (message.size ushr 8 and 0xFF).toByte()
        out[4] = (message.size and 0xFF).toByte()
        message.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * Pulls the first message out of a gRPC response body.
     *
     * Returns `null` for an empty body, which is what a "trailers-only" error response looks
     * like — the caller should be checking the gRPC status in that case anyway.
     */
    fun unframeFirst(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        if (body.size < HEADER_SIZE) throw GrpcException(GrpcStatus.INTERNAL, "gRPC frame header truncated")
        val compressed = body[0].toInt() != 0
        if (compressed) {
            throw GrpcException(GrpcStatus.UNIMPLEMENTED, "compressed gRPC frames are not supported")
        }
        val length = ((body[1].toInt() and 0xFF) shl 24) or
            ((body[2].toInt() and 0xFF) shl 16) or
            ((body[3].toInt() and 0xFF) shl 8) or
            (body[4].toInt() and 0xFF)
        if (length < 0 || HEADER_SIZE + length > body.size) {
            throw GrpcException(GrpcStatus.INTERNAL, "gRPC frame claims $length bytes but body holds ${body.size - HEADER_SIZE}")
        }
        return body.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
    }
}

/** The gRPC status codes this app can meaningfully act on. */
enum class GrpcStatus(val code: Int) {
    OK(0),
    CANCELLED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16);

    companion object {
        fun fromCode(code: Int): GrpcStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

class GrpcException(
    val status: GrpcStatus,
    message: String,
    cause: Throwable? = null,
) : Exception("gRPC ${status.name}: $message", cause)
