package app.stopcast.data

import app.stopcast.domain.StopDisruption
import kotlinx.serialization.Serializable

/**
 * One disruption entry from TfL's `/StopPoint/{id}/Disruption` response. Only [description]
 * is mapped; `ignoreUnknownKeys` drops the rest (type, closureText, appearance, …). A blank
 * description carries nothing to show, so [toStopDisruptionOrNull] drops it.
 */
@Serializable
data class TflStopDisruptionDto(
    val description: String = "",
)

/**
 * The `getFamily=true` response shape: a tree of stop points, each node carrying its own
 * [disruptions] plus its [children] (platforms, entrances, and — with
 * `includeRouteBlockedStops` — route-blocked stops). TfL returns this **object**, not a
 * flat array, whenever `getFamily=true`, which is exactly what the request asks for
 * (a hub id carries few of its own disruptions; the closures live on child platforms —
 * SPEC principle 1). The notices are therefore spread across the tree, so
 * [allDisruptions] walks it and collects them.
 */
@Serializable
data class TflDisruptedPointFamilyDto(
    val disruptions: List<TflStopDisruptionDto> = emptyList(),
    val children: List<TflDisruptedPointFamilyDto> = emptyList(),
)

/**
 * Every non-blank disruption across the family tree, mapped to the domain [StopDisruption].
 * Deduplicated by text: one closure reported against several child platforms would otherwise
 * surface the same notice many times.
 */
fun TflDisruptedPointFamilyDto.allDisruptions(): List<StopDisruption> =
    (disruptions.mapNotNull { it.toStopDisruptionOrNull() } +
        children.flatMap { it.allDisruptions() }).distinct()

/** Maps to the domain [StopDisruption], or null when there's no text worth showing. */
fun TflStopDisruptionDto.toStopDisruptionOrNull(): StopDisruption? =
    description.ifBlank { null }?.let { StopDisruption(description = it) }
