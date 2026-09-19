package app.trackmo.data

import app.trackmo.domain.Departure
import app.trackmo.domain.cleanStopName
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One prediction from TfL's `/StopPoint/{id}/Arrivals` response. Only the fields
 * trackmo maps are declared; the client's `Json { ignoreUnknownKeys = true }`
 * drops the rest (TfL returns ~20 per prediction), so the DTO stays small and
 * tolerant of fields TfL adds later.
 *
 * [expectedArrival] is kept as the raw ISO-8601 string and parsed in [toDeparture]
 * rather than via a custom `Instant` serializer — one obvious place, no serializer
 * wiring.
 */
@Serializable
data class TflArrivalDto(
    val lineId: String = "",
    val lineName: String = "",
    val direction: String? = null,
    val platformName: String? = null,
    val destinationName: String? = null,
    val towards: String? = null,
    val modeName: String = "",
    val expectedArrival: String = "",
)

/**
 * Maps a raw prediction to the domain [Departure]. Destination falls back
 * destinationName → towards → "" (TfL omits destinationName on some services but
 * usually gives `towards`); a blank platform becomes null (buses have none). The
 * resolved destination has its station-type suffix trimmed ([cleanStopName]) the same
 * way stop names are — TfL returns "Brixton Underground Station" / "Lewisham DLR
 * Station" as a `destinationName`, and a departures board reads better as "Brixton" /
 * "Lewisham" (SPEC *Concise copy*). Suffix-only, so a plain destination or a multi-part
 * `towards` ("Pimlico, Grosvenor Road") is left as-is.
 * TfL's `direction` (`inbound`/`outbound`, absent on some services) is retained
 * as the grouping key for per-direction rows (SPEC D8) — normalized to "" when
 * absent or blank, never null, so the grouping key is uniform.
 */
fun TflArrivalDto.toDeparture(): Departure =
    Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction?.trim().orEmpty(),
        destination = cleanStopName(destinationName?.ifBlank { null } ?: towards?.ifBlank { null } ?: ""),
        platform = platformName?.ifBlank { null },
        expectedArrival = Instant.parse(expectedArrival),
        mode = modeName,
    )
