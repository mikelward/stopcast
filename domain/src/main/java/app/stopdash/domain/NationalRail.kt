package app.stopdash.domain

import java.util.concurrent.ConcurrentHashMap
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
 * Where a National Rail station's National Rail times stand after its last fetch: the board came
 * back ([LIVE], so a line with no trains truly has none), the user has set no key so none was asked
 * for ([NO_KEY]), or the board was asked for and failed ([UNAVAILABLE]).
 */
enum class RailFeed { LIVE, NO_KEY, UNAVAILABLE }

/** What a line with no departures shows where its times would be (SPEC *Departures*). */
enum class NoTimes {
    /** Its source answered with no trains: a dash. */
    NO_TRAINS,

    /** A National Rail line with no key set: "No key", a tap away from Settings. */
    NO_KEY,

    /** No source answered for it (no board, or a failed one): "No data". */
    NO_DATA,
    ;

    companion object {
        /**
         * TfL answered for every line but National Rail, whose times come from its own board: a
         * National Rail line has trains to count only when that board came back.
         */
        fun of(row: DepartureRow): NoTimes = when {
            !row.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) -> NO_TRAINS
            row.railFeed == RailFeed.LIVE -> NO_TRAINS
            row.railFeed == RailFeed.NO_KEY -> NO_KEY
            else -> NO_DATA
        }
    }
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
 * National Rail lines' status rows saying "No data" ([RailFeed.UNAVAILABLE]). Everything else is
 * [tfl]'s.
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

    // Each stop's [RailFeed] from its last [arrivals]; absent when none applies (not a National Rail
    // station, or its twin shows the board).
    private val feeds = ConcurrentHashMap<String, RailFeed>()

    override fun railFeed(stopId: String): RailFeed? = feeds[stopId]

    // With National Rail times on, a station's board goes under whichever of its stops this client
    // picked, so its arrivals aren't another client's to reuse; with none, it's TfL's alone.
    override fun shareable(stopId: String): Boolean = !rail.available || codes().crsFor(stopId) == null

    // With a key its stations' boards join TfL's arrivals; without one they don't ("No key").
    override fun arrivalsSource(): Any? = rail.available

    override suspend fun arrivals(stopId: String): List<Departure> {
        val crs = codes().crsFor(stopId)
        if (crs == null || !rail.available) {
            // A station a key would give National Rail times says so; any other stop has none to give.
            if (crs != null) feeds[stopId] = RailFeed.NO_KEY else feeds.remove(stopId)
            return tfl.arrivals(stopId)
        }
        feeds.remove(stopId)
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
                else -> {
                    val board = if (early != null) early.await() else takeHandoff(crs) ?: fetchBoard(crs, stopId)
                    feeds[stopId] = if (board == null) RailFeed.UNAVAILABLE else RailFeed.LIVE
                    fromTfl + board.orEmpty()
                }
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

/** TfL's mode id for National Rail lines, which a Darwin departure carries too. */
const val NATIONAL_RAIL_MODE = "national-rail"

/**
 * The operators whose trains TfL's own feed carries, left out of a National Rail board: London
 * Overground (LO), the Elizabeth line (XR), and London Underground (LT, its London Transport code),
 * which a board lists where tube trains share National Rail platforms (the District at Richmond,
 * the Bakerloo north of Queen's Park). TfL's rows for them carry the real line, its status and its
 * route; a board's copy would add a "London Underground" line (pill "LU") with neither.
 */
val TFL_RUN_OPERATORS = setOf("LO", "XR", "LT")

/**
 * TfL's National Rail line id for each operator's code (Darwin's `operatorCode`), from TfL's
 * `/Line/Mode/national-rail`. The operator's *name* doesn't always slug to it: West Midlands Trains
 * (`LM`) runs as "London Northwestern Railway" / "West Midlands Railway", whose slug TfL doesn't
 * know (every status and route request 404s), and Northern (`NT`) would slug to `northern`, the
 * tube line's id, taking its status. Checked 2026-09-26.
 */
private val TFL_RAIL_LINE_IDS = mapOf(
    "AW" to "transport-for-wales",
    "CC" to "c2c",
    "CH" to "chiltern-railways",
    "EM" to "east-midlands-railway",
    "GC" to "grand-central",
    "GN" to "great-northern",
    "GR" to "london-north-eastern-railway",
    "GW" to "great-western-railway",
    "GX" to "gatwick-express",
    "HT" to "hull-trains",
    "HX" to "heathrow-express",
    "IL" to "island-line",
    "LD" to "lumo",
    "LE" to "greater-anglia",
    "LM" to "west-midlands-trains",
    "ME" to "merseyrail",
    "NT" to "northern-rail",
    "SE" to "southeastern",
    "SN" to "southern",
    "SR" to "scotrail",
    "SW" to "south-western-railway",
    "TL" to "thameslink",
    "TP" to "transpennine-express",
    "VT" to "avanti-west-coast",
    "XC" to "crosscountry",
)

/**
 * A National Rail operator's TfL line id, so its departures and TfL's status and route for the
 * line share one row: TfL's own id for a known [operatorCode], else the operator's name as a slug
 * ("Great Northern" → `great-northern`) — which, for an operator TfL has no line for (the
 * Caledonian Sleeper), TfL answers 404 and the app treats as a line it doesn't know.
 */
fun railLineId(operator: String, operatorCode: String? = null): String =
    operatorCode?.uppercase()?.let { TFL_RAIL_LINE_IDS[it] }
        ?: operator.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
