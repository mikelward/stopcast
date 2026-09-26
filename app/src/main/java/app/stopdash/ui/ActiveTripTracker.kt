package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TflException
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The trip on the way (SPEC *On the way*), process-wide: started from a trip's open route, followed on
 * each [refresh] from its train's calls ([OnTheWay]), and kept on the device ([save]) so it outlives
 * the app being closed. Its [trip] and [progress] feed the trip's screen and the main view's card.
 * The caller drives [refresh] (about every 30 s while shown); nothing here schedules itself.
 */
class ActiveTripTracker(
    // The kept trip, read once on [restore]; blocking, run on [io].
    private val load: () -> ActiveTrip?,
    // Keep the trip, or forget it (null); blocking, run on [io].
    private val save: (ActiveTrip?) -> Unit,
    // A stop's departures, for picking a leg's train.
    private val arrivals: suspend (String) -> List<Departure>,
    private val vehicles: VehicleSource,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Coarse facts only — a line id, an error kind, never a stop or where the rider is going.
    private val warn: (String) -> Unit = {},
    // "Get off soon", once per leg ([OnTheWay.shouldWarn]).
    private val onGetOffSoon: (ActiveTrip, TripProgress.Riding) -> Unit = { _, _ -> },
) {
    private val _trip = MutableStateFlow<ActiveTrip?>(null)
    val trip: StateFlow<ActiveTrip?> = _trip.asStateFlow()

    private val _progress = MutableStateFlow<TripProgress?>(null)
    val progress: StateFlow<TripProgress?> = _progress.asStateFlow()

    // Whether the last refresh couldn't reach TfL: the screen says the trip isn't current.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val lock = Mutex()
    private var restored = false

    /** Read the kept trip, once; a trip started meanwhile wins. */
    suspend fun restore() = lock.withLock {
        if (restored) return@withLock
        restored = true
        val kept = withContext(io) { load() } ?: return@withLock
        if (_trip.value == null) {
            _trip.value = kept
            _progress.value = OnTheWay.advance(kept, null, clock()).second
        }
    }

    /**
     * Start [route] to [destinationName], the rider at its first stop by [readyAt]: its first ride's
     * train is picked on the next [refresh].
     */
    suspend fun start(route: TripRoute, destinationName: String, readyAt: Instant) = lock.withLock {
        restored = true
        val now = clock()
        val first = route.legs.indexOfFirst { !it.isWalk }.coerceAtLeast(0)
        // A walk to the first ride isn't a leg to wait out: its time moves when that ride's train is picked.
        val walked = route.legs.take(first).fold(readyAt) { at, walk -> at.plus(walk.run) }
        val trip = ActiveTrip(route, destinationName, startedAt = now, legIndex = first, legStartedAt = walked)
        keep(trip, OnTheWay.advance(trip, null, now).second)
    }

    /** End the trip: forgotten here and on the device. */
    suspend fun end() = lock.withLock {
        _trip.value = null
        _progress.value = null
        _failed.value = false
        withContext(io) { save(null) }
    }

    /**
     * Bring the trip up to date: pick its leg's train if none is followed (the soonest the rider can
     * catch that runs where they're going), fetch the followed train's calls, and move the trip on.
     * A trip that has arrived is forgotten, its [progress] left at [TripProgress.Arrived] to say so.
     */
    suspend fun refresh() = lock.withLock {
        var trip = _trip.value ?: return@withLock
        var failed = false
        // At most one leg change per refresh: a leg reached here picks its train on the next one.
        val now = clock()
        val leg = trip.leg
        var calls: List<VehicleCall>? = null
        if (leg != null && !leg.isWalk) {
            try {
                if (trip.vehicleId.isBlank()) {
                    val picked = pick(trip, now)
                    if (picked != null) {
                        trip = OnTheWay.follow(trip, picked.first)
                        calls = picked.second
                    }
                } else {
                    calls = vehicles.vehicleCalls(trip.vehicleId, leg.lineId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${leg.lineId}: ${e::class.simpleName}")
                failed = true
            }
        }
        var (next, progress) = OnTheWay.advance(trip, calls, now)
        // A train that turned out not to be the rider's: drop it, so the next refresh picks another.
        // Once on board it stays followed: TfL has only gone quiet on it.
        if (progress is TripProgress.Lost && calls != null && !next.boarded) next = next.copy(vehicleId = "", dueOffAt = null)
        if (progress is TripProgress.Riding && OnTheWay.shouldWarn(next, progress)) {
            onGetOffSoon(next, progress)
            next = OnTheWay.warned(next)
        }
        _failed.value = failed
        if (progress == TripProgress.Arrived) {
            _trip.value = null
            _progress.value = progress
            withContext(io) { save(null) }
        } else {
            keep(next, progress)
        }
    }

    // The soonest train the rider can catch on the trip's leg that runs where they're going, with
    // its calls; null when none of the first few does (or none is due).
    private suspend fun pick(trip: ActiveTrip, now: Instant): Pair<Departure, List<VehicleCall>>? {
        val leg = trip.leg ?: return null
        val readyAt = maxOf(trip.legStartedAt, now)
        val candidates = OnTheWay.candidates(arrivals(leg.fromId), leg, readyAt).take(PICK_TRIES)
        for (train in candidates) {
            val calls = vehicles.vehicleCalls(train.vehicleId, leg.lineId)
            if (OnTheWay.runsAlong(leg, calls)) return train to calls
        }
        return null
    }

    private suspend fun keep(trip: ActiveTrip, progress: TripProgress) {
        val changed = trip != _trip.value
        _trip.value = trip
        _progress.value = progress
        if (changed) withContext(io) { save(trip) }
    }

    companion object {
        // How many of a leg's soonest trains are looked up to find one running where the rider is going.
        const val PICK_TRIES = 3
    }
}
