package io.github.mot1us.spare

/** A live-channel hint must not wait indefinitely for a complete provider catalog. */
class VodAvailability(private val waitMs: Long = 8000) {
    data class Status(val checking: Boolean, val message: String)
    private var identity: Any? = null
    private var began: Long? = null

    fun update(key: Any, now: Long, found: Boolean, complete: Boolean, pending: Boolean, failed: Boolean): Status {
        if (key != identity) { identity = key; began = null }
        if (found) return Status(false, "")
        // A complete index is authoritative even while its refresh is in flight.
        if (complete) return Status(false, "Not available in your VOD library")
        if (pending && began == null) began = now
        val elapsed = began?.let { now - it }
        if (pending && elapsed != null && elapsed in 0 until waitMs) return Status(true, "Checking for VOD…")
        return Status(false, when {
            pending -> "No saved VOD match · Library still updating"
            failed -> "VOD check unavailable · Try Library later"
            else -> "No saved VOD match · Open Library or Search"
        })
    }
}
