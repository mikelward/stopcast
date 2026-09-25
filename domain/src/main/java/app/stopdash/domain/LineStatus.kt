package app.stopdash.domain

/**
 * A line's current TfL status, reduced to what the surfaces need: whether the line is
 * disrupted and a short description to show (SPEC *Disruptions* / D3). TfL reports one or
 * more statuses per line; this carries the worst of them. A line is disrupted whenever any
 * status is not a good service ([GOOD_SERVICE]), so a delayed or suspended line is marked
 * rather than have its countdowns shown as if they could be trusted (SPEC principle 1 —
 * never show a departure stopdash doesn't stand behind).
 *
 * [description] is the short chip label: TfL's own wording for the shown status ("Severe
 * Delays", "Suspended", "Good Service") where that already names the disruption, else a
 * concise label recovered from TfL's free-text reason when the wording is only a vague
 * "Special Service" ([resolveDisruption]).
 *
 * [fullText] is TfL's free-text reason for the shown disruption — the prose behind the chip
 * ("Victoria line: Severe delays while we fix a signal failure…"), for the tap-to-open route
 * detail (SPEC *Disruptions*): the compact card shows only [description], the detail shows
 * this. Null when there is no prose to show — a good service, or a disruption TfL worded but
 * gave no reason for — so the detail then has nothing to expand beyond the label.
 */
data class LineStatus(
    val lineId: String,
    val severity: Int,
    val description: String,
    val fullText: String? = null,
) {
    /** True when TfL reports anything other than a good service on this line. */
    val disrupted: Boolean get() = severity != GOOD_SERVICE

    companion object {
        /** TfL's `statusSeverity` for a normal, undisrupted line. */
        const val GOOD_SERVICE = 10
    }
}
