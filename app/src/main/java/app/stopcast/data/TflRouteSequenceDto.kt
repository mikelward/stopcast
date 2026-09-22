package app.stopcast.data

import app.stopcast.domain.LineRoute
import app.stopcast.domain.LineSequence
import app.stopcast.domain.cleanStopName
import kotlinx.serialization.Serializable

/**
 * TfL's `/Line/{id}/Route/Sequence/{direction}` response, trimmed to what the route detail's stop
 * list needs: each end-to-end route's ordered stop ids, and a name for every stop. The geometry
 * (`lineStrings`) and per-stop line lists are ignored ([Json] `ignoreUnknownKeys`).
 */
@Serializable
data class TflRouteSequenceDto(
    val orderedLineRoutes: List<TflOrderedRouteDto> = emptyList(),
    val stopPointSequences: List<TflStopPointSequenceDto> = emptyList(),
    val stations: List<TflMatchedStopDto> = emptyList(),
) {
    fun toLineSequence(): LineSequence {
        val names = HashMap<String, String>()
        for (stop in stations + stopPointSequences.flatMap { it.stopPoint }) {
            if (stop.id.isNotBlank() && stop.name.isNotBlank()) names.putIfAbsent(stop.id, cleanStopName(stop.name))
        }
        return LineSequence(
            routes = orderedLineRoutes.filter { it.naptanIds.size >= 2 }.map { LineRoute(it.name, it.naptanIds) },
            stopNames = names,
        )
    }
}

@Serializable
data class TflOrderedRouteDto(val name: String = "", val naptanIds: List<String> = emptyList())

@Serializable
data class TflStopPointSequenceDto(val stopPoint: List<TflMatchedStopDto> = emptyList())

@Serializable
data class TflMatchedStopDto(val id: String = "", val name: String = "")
