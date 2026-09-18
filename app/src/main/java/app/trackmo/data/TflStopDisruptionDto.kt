package app.trackmo.data

import app.trackmo.domain.StopDisruption
import kotlinx.serialization.Serializable

/**
 * One entry from TfL's `/StopPoint/{id}/Disruption` response. Only [description] is
 * mapped; `ignoreUnknownKeys` drops the rest (type, closureText, appearance, …). A blank
 * description carries nothing to show, so [toStopDisruptionOrNull] drops it.
 */
@Serializable
data class TflStopDisruptionDto(
    val description: String = "",
)

/** Maps to the domain [StopDisruption], or null when there's no text worth showing. */
fun TflStopDisruptionDto.toStopDisruptionOrNull(): StopDisruption? =
    description.ifBlank { null }?.let { StopDisruption(description = it) }
