package app.stopcast.domain

/**
 * A disruption reported for a whole stop (TfL `/StopPoint/{id}/Disruption`) — a station
 * closure, a moved stop, a blocked entrance. Distinct from a [LineStatus]: a stop can be
 * closed while its lines run normally elsewhere, so a closed stop would otherwise show
 * valid-looking departures you can't actually catch there (SPEC *Disruptions* / D3, the
 * quietly-wrong failure by a different path).
 *
 * [description] is TfL's own human-readable text. StopCast surfaces any stop disruption
 * rather than trying to tell a full closure from a lift outage — TfL's closure fields are
 * coarse and the data is often absent, so surfacing what it does report beats hiding it;
 * classifying severity is left to the full disruptions work (Phase 3).
 */
data class StopDisruption(
    val description: String,
)
