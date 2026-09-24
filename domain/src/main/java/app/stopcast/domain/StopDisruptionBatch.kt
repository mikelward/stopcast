package app.stopcast.domain

/**
 * Which stops' closure checks can share one request ([TflClient.poleDisruptions]). A **bus pole**
 * (a `490…` NaPTAN id, not a `490G…` stop area) carries its own notices and has no children, so
 * several poles go in one `/StopPoint/{ids}/Disruption` call. A station keeps its own family-walking
 * request, since its closures live on child platforms and TfL walks a family for one stop only.
 */
object StopDisruptionBatch {
    /** Poles per request: keeps the URL short (a pole id is ~11 characters) while a junction still fits in one. */
    const val MAX_PER_REQUEST = 20

    fun isPole(stopId: String): Boolean = stopId.startsWith("490") && !stopId.startsWith("490G")
}
