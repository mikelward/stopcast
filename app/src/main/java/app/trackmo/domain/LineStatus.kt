package app.trackmo.domain

/**
 * A line's current TfL status, reduced to what the surfaces need: whether the line is
 * disrupted and a short description to show (SPEC *Disruptions* / D3). TfL reports one or
 * more statuses per line; this carries the worst of them. A line is disrupted whenever any
 * status is not a good service ([GOOD_SERVICE]), so a delayed or suspended line is marked
 * rather than have its countdowns shown as if they could be trusted (SPEC principle 1 —
 * never show a departure trackmo doesn't stand behind).
 *
 * [description] is TfL's own wording for the shown status ("Severe Delays", "Suspended",
 * "Good Service"), kept as TfL spells it.
 */
data class LineStatus(
    val lineId: String,
    val severity: Int,
    val description: String,
) {
    /** True when TfL reports anything other than a good service on this line. */
    val disrupted: Boolean get() = severity != GOOD_SERVICE

    companion object {
        /** TfL's `statusSeverity` for a normal, undisrupted line. */
        const val GOOD_SERVICE = 10
    }
}
