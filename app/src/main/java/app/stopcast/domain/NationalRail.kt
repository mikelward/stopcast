package app.stopcast.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * National Rail's live departure boards (Darwin), by a station's three-letter CRS code (SPEC
 * *National Rail*). [available] is false with no key, and then nothing is asked. Throws a
 * [TflException] on failure, the same contract as [TflClient], so a caller handles both alike.
 */
interface RailBoardSource {
    val available: Boolean
    suspend fun departures(crs: String): List<Departure>
}

/**
 * Each National Rail station's CRS, keyed by its TIPLOC, the code TfL's `910G` stop ids end in
 * (`910GWATRLMN` → `WATRLMN` → `WAT`). From NaPTAN, so the two join exactly; a stop with no entry
 * gets no National Rail times.
 */
class RailStationCodes(private val codes: Map<String, String>) {
    fun crsFor(stopId: String): String? =
        if (stopId.startsWith(TFL_RAIL_PREFIX)) codes[stopId.removePrefix(TFL_RAIL_PREFIX)] else null

    companion object {
        private const val TFL_RAIL_PREFIX = "910G"
        val EMPTY = RailStationCodes(emptyMap())
    }
}

/**
 * A [TflClient] whose [arrivals] at a National Rail station also carry that station's National
 * Rail departures from [rail]: TfL's arrivals feed has none for Great Northern, Thameslink and the
 * rest (SPEC *National Rail*). The TfL-run services Darwin also lists (London Overground, the
 * Elizabeth line) come from TfL only, so none shows twice.
 *
 * TfL's answer decides the stop's outcome as before: its failure fails the stop. A failed rail board
 * is logged ([warn], sanitized: the stop id and reason) and the stop keeps its TfL departures, its
 * National Rail lines showing "No data" as they did without a key. Everything else is [tfl]'s.
 */
class RailAwareTflClient(
    private val tfl: TflClient,
    private val rail: RailBoardSource,
    // Read on the request path (off the main thread), so a bundled table can load lazily.
    private val codes: () -> RailStationCodes,
    private val warn: (String) -> Unit = {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : TflClient by tfl {
    // Each station code's owner: the one stop its board is fetched for and shown under, and when it
    // last asked. Two TfL stops can share a code (St Pancras's two National Rail ids, side by side in
    // every lookup); the board is asked for and joined to only one of them, or its trains would show
    // twice and the user's key be spent twice. The first stop whose own TfL fetch works owns it,
    // and keeps it while it keeps asking, so the trains don't hop between the two; after
    // [OWNER_IDLE_MILLIS] without a request, or once its own TfL fetch fails, another stop may take
    // over.
    private val owners = HashMap<String, Pair<String, Long>>()
    // The standing owner's TfL fetch in progress, per code: completes when it ends, either way.
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()
    // A board a failed owner fetched, handed to the twin that takes over in the same refresh so the
    // station still costs one request; with when it was fetched, and used once.
    private val handoffs = HashMap<String, Pair<List<Departure>, Long>>()
    private val ownersLock = Mutex()

    override suspend fun arrivals(stopId: String): List<Departure> {
        if (!rail.available) return tfl.arrivals(stopId)
        val crs = codes().crsFor(stopId) ?: return tfl.arrivals(stopId)
        return coroutineScope {
            // The standing owner asks for its board alongside TfL, and marks its own fetch in flight;
            // any other stop claims the board only once its own TfL fetch worked. So of two twins
            // refreshed together, the board goes to one whose fetch succeeds, whichever finishes first.
            // Asking renews the owner's hold before its board request goes out, so a twin can't take
            // an idle owner's board over mid-refresh and spend a second request on it.
            val outcome = ownersLock.withLock {
                if (owners[crs]?.first != stopId) return@withLock null
                owners[crs] = stopId to elapsedMillis()
                CompletableDeferred<Unit>().also { inFlight[crs] = it }
            }
            val early = outcome?.let { async { fetchBoard(crs, stopId) } }
            val fromTfl = try {
                tfl.arrivals(stopId)
            } catch (e: CancellationException) {
                outcome?.let { settle(crs, it) }
                throw e
            } catch (e: Exception) {
                // This stop shows nothing now, so let a twin show the board, handing it the board
                // already asked for rather than have it ask again.
                val fetched = early?.await()
                ownersLock.withLock {
                    if (owners[crs]?.first == stopId) owners.remove(crs)
                    if (fetched != null) handoffs[crs] = fetched to elapsedMillis()
                }
                outcome?.let { settle(crs, it) }
                throw e
            }
            // A twin that finishes while the owner is still fetching waits for the owner's fetch to
            // end, then asks again: a failed owner has released the board by then, so the claim
            // succeeds whether or not the twin caught the owner in flight.
            val shows = claim(crs, stopId) || run {
                awaitOwner(crs)
                claim(crs, stopId)
            }
            outcome?.let { settle(crs, it) }
            when {
                !shows -> fromTfl.also { early?.cancel() }
                early != null -> fromTfl + early.await().orEmpty()
                else -> fromTfl + (takeHandoff(crs) ?: fetchBoard(crs, stopId).orEmpty())
            }
        }
    }

    /** [crs]'s board, or null when it failed (logged). */
    private suspend fun fetchBoard(crs: String, stopId: String): List<Departure>? =
        try {
            rail.departures(crs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("national rail board failed for stop $stopId: ${e.message}")
            null
        }

    private suspend fun takeHandoff(crs: String): List<Departure>? = ownersLock.withLock {
        val (board, at) = handoffs.remove(crs) ?: return@withLock null
        board.takeIf { elapsedMillis() - at < HANDOFF_MILLIS }
    }

    /** Waits for the owner fetching for [crs] right now, if any, to finish. */
    private suspend fun awaitOwner(crs: String) {
        ownersLock.withLock { inFlight[crs] }?.await()
    }

    private suspend fun settle(crs: String, outcome: CompletableDeferred<Unit>) {
        ownersLock.withLock { if (inFlight[crs] === outcome) inFlight.remove(crs) }
        outcome.complete(Unit)
    }

    /** Whether [stopId] shows [crs]'s board: it owns it, or takes it over from an idle or no owner. */
    private suspend fun claim(crs: String, stopId: String): Boolean = ownersLock.withLock {
        val now = elapsedMillis()
        val owner = owners[crs]
        val mine = owner == null || owner.first == stopId || now - owner.second >= OWNER_IDLE_MILLIS
        if (mine) owners[crs] = stopId to now
        mine
    }

    companion object {
        /** How long a station code's owning stop keeps it without asking again. */
        const val OWNER_IDLE_MILLIS = 5 * 60_000L

        /** How long a failed owner's board waits for a twin to take it over: one refresh's span. */
        const val HANDOFF_MILLIS = 30_000L
    }
}

/** The operators whose trains TfL's own feed carries, left out of a National Rail board. */
internal val TFL_RUN_OPERATORS = setOf("LO", "XR")

/**
 * A National Rail operator's TfL-style line id ("Great Northern" → `great-northern`), so its
 * departures and TfL's status for the line share one row.
 */
fun railLineId(operator: String): String =
    operator.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
