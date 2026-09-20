package app.stopcast.data

import app.stopcast.domain.Departure
import app.stopcast.domain.LineStatus
import app.stopcast.domain.StopDisruption
import app.stopcast.domain.StopFinder
import app.stopcast.domain.StopLocation
import app.stopcast.domain.TflClient
import app.stopcast.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * The production [TflClient]: Ktor over the OkHttp engine, decoding TfL's JSON
 * through content negotiation (SPEC *Architecture*; the HTTP-engine choice
 * mirrors clothescast). Off the render path — a surface renders the snapshot and
 * a refresh calls this in the background (SPEC *Snapshot-render*).
 *
 * A blank [appKey] means keyless access (SPEC D7): stopcast ships no baked-in key,
 * and a user may supply their own for the higher rate limit. `expectSuccess` makes
 * a non-2xx (e.g. 429 rate-limited) throw, which the caller turns into the honest
 * user-facing state rather than a silent empty list.
 */
class KtorTflClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appKey: String? = null,
) : TflClient, StopFinder {
    override suspend fun arrivals(stopId: String): List<Departure> =
        tflRequest {
            httpClient.get("$baseUrl/StopPoint/$stopId/Arrivals") {
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body<List<TflArrivalDto>>().map { it.toDeparture() }
        }

    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> =
        tflRequest {
            httpClient.get("$baseUrl/StopPoint") {
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("stopTypes", stopTypes.joinToString(","))
                parameter("radius", radiusMeters)
                // The geo search omits each stop's served lines unless asked; without this the
                // stops come back line-less in production (the fixture has them), so a suspended
                // no-prediction line couldn't surface as a status row without a second lookup.
                parameter("returnLines", true)
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body<TflStopPointsResponseDto>().stopPoints.mapNotNull { it.toStopLocationOrNull() }
        }

    override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
        // No lines → no request: a refresh with no predicted lines has nothing to check,
        // and an empty `/Line//Status` path would 404.
        if (lineIds.isEmpty()) return emptyList()
        val ids = lineIds.joinToString(",")
        return tflRequest {
            httpClient.get("$baseUrl/Line/$ids/Status") {
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body<List<TflLineDto>>().mapNotNull { it.toLineStatus() }
        }
    }

    override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
        tflRequest {
            httpClient.get("$baseUrl/StopPoint/$stopId/Disruption") {
                // Both default false, which would miss the very closures this exists to
                // surface (SPEC principle 1): getFamily includes disruptions recorded
                // against a station's child platforms/entrances (a hub id carries few of
                // its own), and includeRouteBlockedStops includes a stop blocked by a
                // route-level disruption.
                parameter("getFamily", true)
                parameter("includeRouteBlockedStops", true)
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body<TflDisruptedPointFamilyDto>().allDisruptions()
        }

    /**
     * Runs a TfL request and maps every transport/decode failure to the domain
     * [TflException] the caller reasons about (offline / rate-limited / unreachable),
     * so both endpoints share one error contract rather than repeating the mapping.
     * Sanitized throughout — a status code or class name, never a payload (SPEC *Privacy*).
     */
    private suspend inline fun <T> tflRequest(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            // Never swallow cancellation — rethrow first so structured concurrency
            // isn't broken (a canceled refresh must actually cancel).
            throw e
        } catch (e: ClientRequestException) {
            // 4xx. 429 is the one the UI treats specially (a user app_key lifts the
            // limit); any other client error is "reached TfL, request rejected".
            throw if (e.response.status == HttpStatusCode.TooManyRequests) {
                TflException.RateLimited(e)
            } else {
                TflException.Unreachable("HTTP ${e.response.status.value}", e)
            }
        } catch (e: ServerResponseException) {
            throw TflException.Unreachable("HTTP ${e.response.status.value}", e)
        } catch (e: UnknownHostException) {
            // No DNS resolution — the device has no network path at all, the
            // conventional "you're offline" signal. Checked before the broader
            // IOException below so a reachable-but-failing TfL isn't mislabeled.
            throw TflException.Offline(e)
        } catch (e: IOException) {
            // Online but the request didn't complete — a connect/read timeout,
            // connection refused, or a TLS failure. TfL (or the path to it) is
            // unreachable, not the device, so this is Unreachable rather than
            // Offline (which would wrongly tell the user they're offline).
            throw TflException.Unreachable("transport: ${e::class.simpleName}", e)
        } catch (e: TflException) {
            throw e
        } catch (e: Exception) {
            // A decode failure or anything else unexpected: reached the client but
            // couldn't produce a result. Sanitized — the class name, no payload.
            throw TflException.Unreachable("unexpected: ${e::class.simpleName}", e)
        }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.tfl.gov.uk"

        /** The production HTTP client: OkHttp engine + lenient JSON, failing on non-2xx. */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                expectSuccess = true
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }
}
