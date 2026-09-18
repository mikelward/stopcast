package app.trackmo.domain

/**
 * Reads live departures from TfL, behind a domain interface so the decision
 * logic (and later the ViewModel) depends on this, not on Ktor or the network —
 * and is tested against recorded fixtures via a fake or a Ktor `MockEngine`
 * (SPEC *Architecture* / *Testing*).
 *
 * Returns departures **unsorted and unfiltered**: ordering, the "Due"/"N min"
 * label, and dropping a service that has gone are the caller's job via
 * [Countdown], recomputed from the current clock (SPEC D4). Implementations
 * throw on a transport or decode failure; the caller decides what the user sees
 * (offline / can't-reach-TfL / rate-limited), per SPEC *When something is wrong*.
 */
interface TflClient {
    suspend fun arrivals(stopId: String): List<Departure>

    /**
     * The current status of each line in [lineIds], from `/Line/{ids}/Status` — one
     * [LineStatus] per line TfL knows, carrying the worst of that line's statuses. An
     * empty [lineIds] makes no request and returns empty. Like [arrivals] it throws on a
     * transport or decode failure; the caller decides what a failed disruption lookup
     * means (SPEC *Disruptions*: mark the affected departures "status unknown", never
     * present them as verified-clean).
     */
    suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus>

    /**
     * Disruptions reported for the stop [stopId], from `/StopPoint/{id}/Disruption` — a
     * closure or stop-level notice, empty when the stop is clear. Per stop (the endpoint
     * scopes to it), so a closed stop is flagged even when its lines run normally (SPEC
     * *Disruptions*). Throws on a transport/decode failure, like [arrivals].
     */
    suspend fun stopDisruptions(stopId: String): List<StopDisruption>
}
