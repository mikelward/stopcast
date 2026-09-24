package app.stopcast.data

import app.stopcast.domain.StationMatch
import app.stopcast.domain.cleanStopName
import kotlinx.serialization.Serializable

/**
 * TfL's `/StopPoint/Search?query=` response — the name search behind "Find a station" (SPEC
 * *Finding stops*). Only the fields stopcast maps are declared; the client ignores the rest.
 */
@Serializable
data class TflSearchResponseDto(
    val matches: List<TflSearchMatchDto> = emptyList(),
)

/** One search match: a hub, station or stop [id], its [name], and the [modes] it serves. */
@Serializable
data class TflSearchMatchDto(
    val id: String = "",
    val name: String = "",
    val modes: List<String> = emptyList(),
)

/** The domain [StationMatch], or null without an id or a usable name. */
fun TflSearchMatchDto.toStationMatchOrNull(): StationMatch? {
    val cleaned = cleanStopName(name)
    if (id.isBlank() || cleaned.isBlank()) return null
    return StationMatch(id = id, name = cleaned, modes = modes)
}
