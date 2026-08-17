package dev.starlinkguard.core.grpc

import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus

/**
 * Talks to a Starlink dish.
 *
 * Kept as an interface so the detector and the monitoring loop can be exercised against fakes
 * without a dish on the other end of the Wi-Fi.
 */
interface DishClient {

    /** Fetches dish status. Throws on transport failure or a non-OK gRPC status. */
    suspend fun status(): DishStatus

    /**
     * Fetches the dish's own GPS fix.
     *
     * Returns `null` when the dish declines to answer, which is the common case: the RPC is
     * `PERMISSION_DENIED` unless the owner has turned on *Starlink app → Settings → Advanced
     * → Debug Data → Starlink Location*, and on many service plans the data is not exposed at
     * all. Callers must treat a missing fix as normal and fall back to orientation.
     */
    suspend fun location(): DishLocation?

    fun close()
}
