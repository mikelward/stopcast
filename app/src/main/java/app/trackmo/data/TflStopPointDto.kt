package app.trackmo.data

import app.trackmo.domain.LineRef
import app.trackmo.domain.StopLocation
import app.trackmo.domain.cleanStopName
import kotlinx.serialization.Serializable

/**
 * TfL's `/StopPoint?lat=&lon=&radius=` response — the nearby-stops search behind the
 * in-app "near me now" list (SPEC *Finding stops*). Only the fields trackmo maps are
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
)

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
 * identity trackmo needs (a blank id, or no usable name). The name is cleaned of TfL's
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
    )
}
