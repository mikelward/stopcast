package app.stopcast.domain

/**
 * A **dismissed alert**: the user tapped "dismiss" on a stop-closure card and it should stay
 * hidden — but only until the alert's *content* changes, so a dismiss clears the notice you've
 * read without ever burying a new or escalated one (maintainer, 2026-09-22).
 *
 * [alertKey] identifies **which** alert, stably across refreshes: the stop-status row's place
 * identity ([stopPlaceKey] — hub, else a real StopArea, else the stop), the same key the near-me
 * fold collapses on, so the dismissal survives the nearest-member changing as the user moves and
 * doesn't leak to a same-text notice at another place. [contentSignature] is **what must stay
 * unchanged** to remain dismissed — the normalized (pre-name-strip) notice text; when TfL rewords
 * or replaces the notice the signature no longer matches and the card returns. (Line-status alerts
 * would fold their severity into the signature too — a `TODO.md` follow-up; today only stop
 * closures are dismissible.)
 *
 * Persisted so a dismiss survives restart; it rides Android backup/transfer with the rest of the
 * user's config (SPEC *Privacy*), never an app-initiated send — the notice text is TfL's own public
 * wording and the key is a public place id, no coordinate.
 */
data class DismissedAlert(
    val alertKey: String,
    val contentSignature: String,
) {
    companion object {
        /**
         * The dismissal identity of a **stop-closure row** — its place key and the normalized
         * notice text. Only meaningful for a stop-status row (a non-null [DepartureRow.stopDisruption]);
         * a caller filters to those first.
         */
        fun ofStopClosure(row: DepartureRow): DismissedAlert =
            DismissedAlert(alertKey = stopPlaceKey(row), contentSignature = row.stopDisruption.orEmpty())
    }
}

/**
 * Pure rules for the dismissed set, kept out of the store so the membership logic is JVM-testable
 * without DataStore or Android (mirrors [Starred]/[WatchedStops]).
 */
object Dismissed {
    /**
     * [current] with [alert] recorded. A dismiss only **adds**, so dismissing one of several cards
     * shown at a place (the fold keeps a card per distinct notice — [DepartureRows.nearbyDeduped])
     * never un-dismisses the others; obsolete entries are pruned by [reconcile] after a refresh.
     */
    fun dismiss(current: Set<DismissedAlert>, alert: DismissedAlert): Set<DismissedAlert> =
        current + alert

    /**
     * [current] pruned to the notices still shown, but **only for places actually checked this
     * cycle** ([checkedPlaces] — the place keys whose stops were all queried and whose disruption
     * lookup succeeded). An entry for a checked place whose signature is no longer in [live] — its
     * notice resolved or was replaced — is dropped; an entry for a place **not** checked (a different
     * nearby set not queried this cycle, or one whose disruption lookup failed) is **kept**, so a
     * still-valid dismissal never lapses just because its place wasn't looked at. This stops a
     * resolved incident's stale `(place, text)` from later suppressing a genuinely new same-text
     * closure (SPEC principle 2 — never hide a warning) without breaking persist-until-change. A
     * concurrent notice still shown keeps its dismissal, since its signature is in [live].
     */
    fun reconcile(
        current: Set<DismissedAlert>,
        live: Set<DismissedAlert>,
        checkedPlaces: Set<String>,
    ): Set<DismissedAlert> =
        current.filterTo(mutableSetOf()) { it in live || it.alertKey !in checkedPlaces }
}

/**
 * The stable place identity a stop-status row folds and dismisses on: the interchange
 * ([DepartureRow.hubId]) when there is one, else a **real** StopArea cluster
 * ([DepartureRow.clusterId] when it is TfL's `stationNaptan`, not the display-name fallback — a
 * naptan code never equals a display name, so `clusterId != stopName` marks a real one), else the
 * stop's own id. Shared by the near-me fold ([DepartureRows.nearbyDeduped]) and the dismissal key
 * so the two agree on what "one place" means.
 */
fun stopPlaceKey(row: DepartureRow): String =
    stopPlaceKey(row.hubId, row.clusterId, row.stopName, row.stopId)

/** The place identity of a [StopArrivals] — the same rule as the [DepartureRow] overload, so a stop
 *  and the rows derived from it agree on their place (used to scope the dismissal [Dismissed.reconcile]). */
fun stopPlaceKey(stop: StopArrivals): String =
    stopPlaceKey(stop.hubId, stop.clusterId, stop.stopName, stop.stopId)

/**
 * The place identity from the raw stop-identity fields — the interchange ([hubId]) when there is
 * one, else a real StopArea cluster ([clusterId] when it is a `stationNaptan`, not the display-name
 * fallback), else the stop's own id. The one source of truth the row / [StopArrivals] overloads and
 * the ViewModel's queried-stop scope all call, so fold, dismiss, and reconcile agree on "one place".
 */
fun stopPlaceKey(hubId: String, clusterId: String, stopName: String, stopId: String): String {
    val realCluster = clusterId.takeUnless { it.isBlank() || it == stopName }
    return hubId.ifBlank { realCluster ?: stopId }
}
