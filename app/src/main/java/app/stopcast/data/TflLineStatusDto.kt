package app.stopcast.data

import app.stopcast.domain.LineStatus
import app.stopcast.domain.mostSevereDisruption
import app.stopcast.domain.resolveDisruption
import kotlinx.serialization.Serializable

/**
 * One line from TfL's `/Line/{ids}/Status` response. Only the fields stopcast maps are
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
    // TfL's full human-readable text for this status. Vague on its own for buses (the
    // description is often just "Special Service"), so it is scanned to name the actual
    // disruption ("Diversion") — see [resolveDisruption].
    val reason: String = "",
)

/**
 * Reduces a line's statuses to the one the surfaces show: its worst disruption if any, else
 * good service — or **null when TfL reported no status at all**.
 *
 * Every non-good entry is resolved to a `(label, severity)` by [resolveDisruption] — TfL's
 * own wording where it names the disruption, a concise label read from the reason where the
 * wording is only a vague "Special Service" — and the most severe of them (lowest severity)
 * is shown. Reducing every entry to the same shape is what lets a diversion inferred from a
 * severity-0 catch-all be compared honestly against a graded delay: the catch-all neither
 * always wins (severity 0 sorts as most severe) nor is discarded when it is in fact the
 * worse of the two. A disrupted line is always kept disrupted — its countdowns are never
 * presented as verified-clean (SPEC principle 1).
 *
 * TfL's `isNow` flag is deliberately **not** used to hide a "future" alert: it reads `false`
 * even for planned closures currently in effect (a Sunday Overground closure in progress),
 * so it marks "unplanned", not "current", and filtering on it would hide live disruptions —
 * telling current from future needs the dates in the text.
 *
 * An **empty `lineStatuses`** (which the tolerant DTO accepts) is *unknown*, not good
 * service: manufacturing a clean status from absent data would show ordinary countdowns for
 * a line TfL never verified (SPEC principle 1). Returning null lets the caller treat that
 * line as unchecked — the "status unknown" path — rather than verified-clean.
 */
fun TflLineDto.toLineStatus(): LineStatus? {
    if (lineStatuses.isEmpty()) return null
    val worst = mostSevereDisruption(
        lineStatuses
            .filter { it.statusSeverity != LineStatus.GOOD_SERVICE }
            .map { resolveDisruption(it.statusSeverityDescription, it.statusSeverity, it.reason) },
    )
    return if (worst != null) {
        LineStatus(
            lineId = id,
            severity = worst.severity,
            description = worst.label,
            // The chosen disruption's prose, for the route detail view; null when TfL named
            // the status but gave no reason (nothing to expand beyond the chip label).
            fullText = worst.fullText.ifBlank { null },
        )
    } else {
        // Non-empty, all good service: name the good status.
        LineStatus(
            lineId = id,
            severity = LineStatus.GOOD_SERVICE,
            description = lineStatuses.first().statusSeverityDescription.ifBlank { "Good Service" },
        )
    }
}
