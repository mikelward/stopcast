package app.stopcast.data

import app.stopcast.domain.StopDisruption
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One disruption entry from TfL's `/StopPoint/{id}/Disruption` response. [description] and its
 * `fromDate`/`toDate` window are mapped; `ignoreUnknownKeys` drops the rest (type, closureText,
 * appearance, …). A blank description carries nothing to show, so [toStopDisruptionOrNull] drops
 * it. The dates stay raw strings here and are parsed in the mapping, like [TflArrivalDto].
 */
@Serializable
data class TflStopDisruptionDto(
    // The stop the entry is recorded against: how a multi-stop response ([KtorTflClient.poleDisruptions])
    // is attributed back to each requested pole. Blank in the family tree, where the node says.
    val atcoCode: String = "",
    val description: String = "",
    val fromDate: String? = null,
    val toDate: String? = null,
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
 * Deduplicated: one closure reported against several child platforms would otherwise surface the
 * same notice many times.
 */
fun TflDisruptedPointFamilyDto.allDisruptions(onBadDate: (String) -> Unit = {}): List<StopDisruption> =
    (disruptions.mapNotNull { it.toStopDisruptionOrNull(onBadDate) } +
        children.flatMap { it.allDisruptions(onBadDate) }).distinct()

/** Maps to the domain [StopDisruption], or null when there's no text worth showing. */
fun TflStopDisruptionDto.toStopDisruptionOrNull(onBadDate: (String) -> Unit = {}): StopDisruption? =
    description.ifBlank { null }?.let {
        StopDisruption(
            description = it,
            validFrom = parseTflInstant(fromDate, onBadDate),
            validTo = parseTflInstant(toDate, onBadDate),
        )
    }

/**
 * TfL's ISO-8601 UTC timestamp ("2026-01-01T09:00:00Z"), or null when absent or unparseable. An
 * unreadable bound becomes open-ended, so the notice is still shown rather than dropped (SPEC
 * principle 1) — hiding a closure over a date-format change is the worse failure — and is
 * reported to [onBadDate] so the fallback isn't silent.
 */
internal fun parseTflInstant(raw: String?, onBadDate: (String) -> Unit = {}): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        onBadDate(raw)
        null
    }
}
