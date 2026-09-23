package app.stopcast.data

import app.stopcast.domain.Departure
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.JourneyCall
import app.stopcast.domain.LineRef
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.WidgetJourney
import app.stopcast.domain.normalizeBranch
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of a [DeparturesSnapshot], kept in the `data` layer so the domain types
 * stay free of serialization annotations and `java.time` never has to round-trip through a
 * serializer. `Instant`s are stored as epoch milliseconds (a plain `Long`), which is stable
 * across app versions and time zones. `version` lets a future format change be detected and
 * discarded rather than mis-read; an unknown version reads as "no snapshot".
 *
 * These are a private persistence detail — this code opens no off-device channel of its own,
 * so the field set can change with a `version` bump, and only the fields the restore actually
 * needs are carried (the transient refresh-cycle flags are not persisted; see
 * [DeparturesSnapshot]). The file itself is not strictly device-local: it rides Android backup
 * and device-to-device transfer like the rest of the app's data (SPEC §12), a platform path
 * the user controls — see [DataStoreSnapshotStore].
 */
@Serializable
internal data class PersistedSnapshot(
    val version: Int = CURRENT_VERSION,
    val stops: List<PersistedStop> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    // The widget's starred journeys and journey-only stops ([DeparturesSnapshot]), added in version
    // 2. Defaulted, so a version-1 snapshot reads back with none.
    val journeys: List<PersistedWidgetJourney> = emptyList(),
    val journeyOnlyStopIds: List<String> = emptyList(),
) {
    companion object {
        /**
         * The current on-disk format. Bump when a field's meaning changes incompatibly. Version 2
         * added journey-only stops: a version-1 reader would show them as nearby stops, so it
         * must discard a version-2 file, while this build still reads version 1.
         */
        const val CURRENT_VERSION = 2

        /** The formats this build reads. */
        val READABLE_VERSIONS = setOf(1, CURRENT_VERSION)
    }
}

@Serializable
internal data class PersistedStop(
    val stopId: String,
    val stopName: String,
    val departures: List<PersistedDeparture> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    val lines: List<PersistedLine> = emptyList(),
    val arrivalsFresh: Boolean = true,
    // The stop's cluster for per-place grouping (SPEC D8). Defaulted, so a snapshot written by an
    // older build reads back blank — that stop groups on its own until the next refresh restamps
    // it — no version bump needed.
    val clusterId: String = "",
    // The bus pole's letter/bearing/towards for the per-pole split (SPEC D8) — persisted like
    // [clusterId] (grouping inputs the screen needs before the refresh), so a restored snapshot keeps
    // its per-pole headers rather than collapsing to bare/terminus until the fetch lands. Defaulted,
    // so an older build's snapshot reads back blank — no version bump needed.
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
)
// Stop disruptions (closures) are deliberately NOT persisted: a closure is a point-in-time
// claim the screen renders unconditionally, with no stale-safe rendering (unlike a countdown,
// which is withheld once stale), so a saved one would assert a possibly-reopened station on
// the next launch. This is the same reason Snapshot.mergeStop never ages a disruption. On
// restore a stop carries no disruptions; the immediate refresh re-establishes them.

@Serializable
internal data class PersistedWidgetJourney(
    val originId: String,
    val calls: List<PersistedJourneyCall> = emptyList(),
    val key: String = "",
    val shownFrom: String = "",
)

@Serializable
internal data class PersistedJourneyCall(
    val lineId: String,
    val destination: String,
    val branch: String? = null,
)

@Serializable
internal data class PersistedDeparture(
    val lineId: String,
    val lineName: String,
    val direction: String,
    val destination: String,
    val platform: String? = null,
    val expectedArrivalMillis: Long,
    val mode: String,
    // The "via" branch, when TfL gave one. Nullable with a default, so a snapshot written by
    // an older build (no branch field) reads back as null — restored departures show no branch
    // until the next refresh, no version bump needed.
    val branch: String? = null,
)

@Serializable
internal data class PersistedLine(
    val id: String,
    val name: String,
    val mode: String,
)

internal fun WidgetJourney.toPersisted(): PersistedWidgetJourney =
    PersistedWidgetJourney(originId, calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, key, shownFrom)

internal fun DeparturesSnapshot.toPersisted(): PersistedSnapshot =
    PersistedSnapshot(
        stops = stops.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        journeys = journeys.map { j ->
            PersistedWidgetJourney(
                j.originId, j.calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.sorted(),
    )

/**
 * The domain snapshot, or null when the stored format is a version this build doesn't know
 * — discarded rather than mis-read, so a forward-incompatible change fails safe to "no
 * last-good".
 */
internal fun PersistedSnapshot.toDomain(): DeparturesSnapshot? {
    if (version !in PersistedSnapshot.READABLE_VERSIONS) return null
    return DeparturesSnapshot(
        stops = stops.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        journeys = journeys.map { j ->
            WidgetJourney(
                j.originId, j.calls.mapTo(HashSet()) { JourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.toSet(),
    )
}

internal fun StopArrivals.toPersisted(): PersistedStop =
    PersistedStop(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        lines = lines.map { PersistedLine(it.id, it.name, it.mode) },
        // Stop disruptions are intentionally not carried to disk (point-in-time; see above).
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
    )

private fun PersistedStop.toDomain(): StopArrivals =
    StopArrivals(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        lines = lines.map { LineRef(it.id, it.name, it.mode) },
        // No disruptions restored — the immediate refresh re-establishes any current one.
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
    )

private fun Departure.toPersisted(): PersistedDeparture =
    PersistedDeparture(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrivalMillis = expectedArrival.toEpochMilli(),
        mode = mode,
        branch = branch,
    )

private fun PersistedDeparture.toDomain(): Departure =
    Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = Instant.ofEpochMilli(expectedArrivalMillis),
        mode = mode,
        // Fold an older build's raw spelling ("Charing Cross", "Bank Branch") to the canonical
        // short label on restore, so a persisted row matches a freshly-fetched one (SPEC).
        branch = normalizeBranch(branch),
    )
