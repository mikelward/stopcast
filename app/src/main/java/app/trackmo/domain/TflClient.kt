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
}
