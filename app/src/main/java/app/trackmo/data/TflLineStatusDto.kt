package app.trackmo.data

import app.trackmo.domain.LineStatus
import kotlinx.serialization.Serializable

/**
 * One line from TfL's `/Line/{ids}/Status` response. Only the fields trackmo maps are
 * declared; the client's `Json { ignoreUnknownKeys = true }` drops the rest. A line
 * carries a list of [TflLineStatusEntryDto] — usually one ("Good Service"), several when
 * disrupted.
 */
@Serializable
data class TflLineDto(
    val id: String = "",
    val name: String = "",
    val lineStatuses: List<TflLineStatusEntryDto> = emptyList(),
)

@Serializable
data class TflLineStatusEntryDto(
    val statusSeverity: Int = LineStatus.GOOD_SERVICE,
    val statusSeverityDescription: String = "",
)

/**
 * Reduces a line's statuses to the one the surfaces show: its worst disruption if any,
 * else good service — or **null when TfL reported no status at all**. A status is a
 * disruption when its severity is not [GOOD_SERVICE] ([LineStatus] treats any non-good
 * severity as disrupted), so this filters to those and picks the lowest severity — the
 * most disruptive of the delay range TfL reports — rather than letting a coexisting
 * good-service entry mask a real disruption.
 *
 * An **empty `lineStatuses`** (which the tolerant DTO accepts) is *unknown*, not good
 * service: manufacturing a clean status from absent data would show ordinary countdowns
 * for a line TfL never verified (SPEC principle 1). Returning null lets the caller treat
 * that line as unchecked — the "status unknown" path — rather than verified-clean.
 */
fun TflLineDto.toLineStatus(): LineStatus? {
    if (lineStatuses.isEmpty()) return null
    val worst = lineStatuses
        .filter { it.statusSeverity != LineStatus.GOOD_SERVICE }
        .minByOrNull { it.statusSeverity }
    return if (worst != null) {
        LineStatus(
            lineId = id,
            severity = worst.statusSeverity,
            description = worst.statusSeverityDescription.ifBlank { "Disrupted" },
        )
    } else {
        // Non-empty, all good service: first() is safe and names the good status.
        LineStatus(
            lineId = id,
            severity = LineStatus.GOOD_SERVICE,
            description = lineStatuses.first().statusSeverityDescription.ifBlank { "Good Service" },
        )
    }
}
