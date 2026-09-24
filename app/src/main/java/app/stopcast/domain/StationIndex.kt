package app.stopcast.domain

/**
 * One station or interchange in the bundled index (SPEC *Finding stops → Find a station*): its
 * TfL [id], cleaned [name], the [modes] it serves, and the interchange it belongs to ([hubId],
 * blank for none — and for a hub itself). Public TfL facts, never user data.
 */
data class IndexedStation(
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
    val hubId: String = "",
)

/**
 * The bundled list of London's stations and interchanges — every tube, DLR, Overground, Elizabeth
 * line, tram, rail and pier stop, not the ~20,000 bus stops — searched on the device as the user
 * types, so an abbreviation or a station code ("kx", "kgx") finds its station with no request and
 * no wait. TfL's own search still covers what the index doesn't (bus stops); [rank] orders both.
 */
class StationIndex(val stations: List<IndexedStation>) {
    /**
     * The stations matching [query], best first ([rank]), at most [limit]. A station whose
     * interchange also matches is left out: the interchange's page already holds it, and listing
     * both reads as the same place twice ("King's Cross St. Pancras" hub and tube station).
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<StationMatch> {
        val scored = stations.mapNotNull { station ->
            StationMatcher.tier(query, station.name, station.id, station.hubId)?.let { station to it }
        }
        val matchedIds = scored.mapTo(HashSet()) { it.first.id }
        return scored
            .filter { (station, _) -> station.hubId.isBlank() || station.hubId !in matchedIds }
            .sortedWith(compareBy({ it.second }, { !it.first.isHub }, { it.first.name.length }, { it.first.name }))
            .take(limit)
            .map { (station, _) -> StationMatch(station.id, station.name, station.modes) }
    }

    /**
     * The index's matches first, then TfL's that the index doesn't hold (bus stops, or a station
     * newer than the bundled list), each placed by how well its own name matches — so a bus stop
     * named for the query still sorts among the stations, and one TfL found by a rule this matcher
     * doesn't share goes last rather than being dropped. Deduplicated by id, and a TfL match that
     * the index knows belongs to an interchange already listed stays folded into it, as in [search].
     */
    fun rank(query: String, local: List<StationMatch>, remote: List<StationMatch>, limit: Int = DEFAULT_LIMIT): List<StationMatch> {
        val localIds = local.mapTo(HashSet()) { it.id }
        val extra = remote
            .filter { it.id !in localIds && hubOf[it.id]?.let { hub -> hub in localIds } != true }
            .distinctBy { it.id }
        // Local order is already best-first; a remote match slots in after every local match of
        // the same or better tier, so the index's tie-breaks (hub first, shorter name) hold.
        val localTiers = local.map { StationMatcher.tier(query, it.name, it.id) ?: StationMatchTier.Fuzzy }
        val merged = local.mapIndexed { i, m -> Triple(m, localTiers[i].ordinal, i) } +
            extra.mapIndexed { i, m ->
                val tier = StationMatcher.tier(query, m.name, m.id)?.ordinal ?: StationMatchTier.entries.size
                Triple(m, tier, local.size + i)
            }
        return merged.sortedWith(compareBy({ it.second }, { it.third })).take(limit).map { it.first }
    }

    // Each indexed station's interchange, for folding TfL's matches the way [search] folds its own.
    private val hubOf: Map<String, String> =
        stations.filter { it.hubId.isNotBlank() }.associate { it.id to it.hubId }

    companion object {
        const val DEFAULT_LIMIT = 20
        val EMPTY = StationIndex(emptyList())
    }
}

private val IndexedStation.isHub: Boolean get() = id.startsWith("HUB", ignoreCase = true)
