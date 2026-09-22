package app.stopcast.domain

/**
 * Reads live departures from TfL, behind a domain interface so the decision
 * logic (and later the ViewModel) depends on this, not on Ktor or the network —
 * and is tested against recorded fixtures via a fake or a Ktor `MockEngine`
 * (SPEC *Architecture* / *Testing*).
 *
 * Returns departures **unsorted and unfiltered**: ordering, the "0 min"/"N min"
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

    /**
     * The interchange [hubId] (TfL `hubNaptanCode`) resolved from `/StopPoint/{hubId}`: its display
     * [HubInfo.name] — e.g. "King's Cross & St Pancras International" for `HUBKGX`, cleaned of its
     * type suffix like a stop name — plus [HubInfo.aliases], every member station's cleaned name.
     * The name titles an interchange's folded disruption by the interchange rather than one member
     * stop; the aliases let the display strip drop a redundant leading name even when the notice
     * uses a different member's spelling than the watched stop (SPEC *Disruptions*). The real client
     * throws on a transport/decode failure, like [arrivals]; the caller falls back to the stop's own
     * name so a failed lookup never blanks the alert. Defaults to empty so a client that doesn't
     * enrich hubs, and a test fake, take the same safe fallback without implementing it.
     */
    suspend fun hubInfo(hubId: String): HubInfo = HubInfo()
}

/** An interchange's resolved display [name] and every member-station [aliases] spelling. */
data class HubInfo(val name: String = "", val aliases: List<String> = emptyList())
