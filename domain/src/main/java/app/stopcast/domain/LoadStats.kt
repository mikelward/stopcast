package app.stopcast.domain

/**
 * One line for the on-device debug log describing a departures fetch (departures-only, and labeled so): how many TfL requests it
 * made, of which kinds, how long it took, and how much of that was spent waiting on the shared rate
 * limiter. Counts and durations only — never a stop, coordinate, or key (`docs/PRIVACY.md`). The
 * nearby-stop lookup that precedes a near-me load is one more request, not counted here.
 */
object LoadStats {
    data class Requests(
        val departures: Int = 0,
        val closures: Int = 0,
        val closureBatches: Int = 0,
        val lineStatus: Int = 0,
        val hubs: Int = 0,
    ) {
        val total: Int get() = departures + closures + closureBatches + lineStatus + hubs
    }

    fun describe(requests: Requests, elapsedMillis: Long, rateWaitMillis: Long): String =
        "departures fetch: ${requests.total} requests " +
            "(${requests.departures} departures, ${requests.closures} closure, " +
            "${requests.closureBatches} closure batch, ${requests.lineStatus} line status, ${requests.hubs} hub) " +
            "in $elapsedMillis ms, ${rateWaitMillis.coerceAtLeast(0)} ms rate-limited"
}
