package app.stopcast.data

import app.stopcast.domain.LineRef
import app.stopcast.domain.StopLocation
import app.stopcast.domain.cleanStopName
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * TfL's `/StopPoint?lat=&lon=&radius=` response — the nearby-stops search behind the
 * in-app "near me now" list (SPEC *Finding stops*). Only the fields stopcast maps are
 * declared; the client's `Json { ignoreUnknownKeys = true }` drops the rest (TfL returns
 * ~20 per stop, plus a paging envelope), so the DTO stays small and tolerant of fields
 * TfL adds later.
 */
@Serializable
data class TflStopPointsResponseDto(
    val stopPoints: List<TflStopPointDto> = emptyList(),
)

/**
 * One stop from the nearby search. [id] and [naptanId] are the same value in practice;
 * [id] is preferred and [naptanId] is the fallback. [lineModeGroups] groups the served
 * [lines] by mode, which is how a line's mode is recovered — the [lines] entries
 * themselves carry no mode (SPEC *Departures*: mode colors the row).
 */
@Serializable
data class TflStopPointDto(
    val id: String = "",
    val naptanId: String = "",
    val commonName: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val modes: List<String> = emptyList(),
    val lines: List<TflStopLineDto> = emptyList(),
    val lineModeGroups: List<TflLineModeGroupDto> = emptyList(),
    // TfL's parent cluster for this stop: a bus junction's poles and a station's platforms share
    // it (e.g. `490G000804`, `940GZZLUKSX`). Often blank for a bus pole with no assigned StopArea;
    // then the grouping falls back to the display name. Used only for grouping (SPEC D8), never a
    // coordinate.
    val stationNaptan: String = "",
    // TfL's interchange for this stop, above `stationNaptan`: `HUBKGX` ties King's Cross and St
    // Pancras together. Blank for a stop in no hub. Used to fold an interchange's shared disruption
    // (SPEC *Disruptions*); a public id, never a coordinate.
    val hubNaptanCode: String = "",
    // The bus stop's pole letter — the "D" a rider reads on the physical stop ("Stop D") — for the
    // per-pole bus header (SPEC D8: "King's Cross Station (D)"). Blank for a stop with no letter (a
    // station, or a bus stop TfL gives none). A public fact about the stop, never a coordinate.
    val stopLetter: String = "",
    // TfL's per-stop key/value extras. The one stopcast reads is the **CompassPoint** (the pole's
    // bearing, "E"/"SW"), the fallback bus header cue when a stop has no letter ([compassBearing]).
    val additionalProperties: List<TflAdditionalPropertyDto> = emptyList(),
    // The member stop points nested under this one — populated when TfL returns a hub tree from
    // `/StopPoint/{hubId}` (the interchange's stations and their platforms). Walked to collect every
    // member station's name for the disruption strip's alias set ([hubStationNames]); empty in the
    // flat nearby-search response.
    val children: List<TflStopPointDto> = emptyList(),
)

/** One TfL `additionalProperties` entry. stopcast reads only the `CompassPoint` [key]. */
@Serializable
data class TflAdditionalPropertyDto(
    val category: String = "",
    val key: String = "",
    val value: String = "",
)

/** The pole's compass bearing ("E", "SW") from TfL's `CompassPoint` property, else blank. */
fun TflStopPointDto.compassBearing(): String =
    additionalProperties.firstOrNull { it.key.equals("CompassPoint", ignoreCase = true) }?.value.orEmpty()

/**
 * Every distinct station-name spelling in this hub tree — this node's cleaned [commonName] plus all
 * descendants' — for the disruption strip's alias set. TfL spells one interchange a dozen ways
 * ("King's Cross St. Pancras Underground Station", "London St Pancras International LL Rail Station",
 * "St Pancras Intern'l & King's X Stns"); the union lets the strip drop whichever spelling a notice
 * leads with. Blank names are dropped and the rest deduped. Public station names, safe to carry.
 */
fun TflStopPointDto.hubStationNames(): List<String> =
    (listOf(commonName) + children.flatMap { it.hubStationNames() })
        .map { cleanStopName(it) }
        .filter { it.isNotBlank() }
        .distinct()

@Serializable
data class TflStopLineDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class TflLineModeGroupDto(
    val modeName: String = "",
    val lineIdentifier: List<String> = emptyList(),
)

/**
 * Maps a nearby-search stop to the domain [StopLocation], or null when it lacks the
 * identity stopcast needs (a blank id, or no usable name). The name is cleaned of TfL's
 * type suffix ([cleanStopName]); each line's mode is recovered from [lineModeGroups],
 * falling back to the stop's primary mode, so a line still colors correctly at a
 * single-mode stop where the groups are redundant.
 */
fun TflStopPointDto.toStopLocationOrNull(): StopLocation? {
    val stopId = id.ifBlank { naptanId }
    val stopName = cleanStopName(commonName)
    if (stopId.isBlank() || stopName.isBlank()) return null
    val modeByLineId = buildMap {
        for (group in lineModeGroups) {
            for (lineId in group.lineIdentifier) put(lineId, group.modeName)
        }
    }
    val primaryMode = modes.firstOrNull().orEmpty()
    return StopLocation(
        id = stopId,
        name = stopName,
        latitude = lat,
        longitude = lon,
        lines = lines
            .filter { it.id.isNotBlank() }
            .map { LineRef(id = it.id, name = it.name, mode = modeByLineId[it.id] ?: primaryMode) },
        // Cluster by TfL's StopArea/parent where it gives one, else by the cleaned name so a
        // station's same-named poles still merge (SPEC *Finding stops*). Keying on the id when TfL
        // provides it is what keeps a station whose name it spells several ways together.
        clusterId = stationNaptan.ifBlank { stopName },
        hubId = hubNaptanCode,
        // The pole's letter and bearing, for the per-pole bus header (SPEC D8). Both blank for a
        // station or a bus stop TfL gives neither.
        stopLetter = stopLetter.trim(),
        bearing = compassBearing().trim().uppercase(Locale.ROOT),
    )
}
