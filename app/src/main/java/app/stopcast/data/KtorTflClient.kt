package app.stopcast.data

import app.stopcast.domain.Departure
import app.stopcast.domain.HubInfo
import app.stopcast.domain.LineSequence
import app.stopcast.domain.LineStatus
import app.stopcast.domain.RouteSequenceSource
import app.stopcast.domain.StopDisruption
import app.stopcast.domain.StopFinder
import app.stopcast.domain.StopLocation
import app.stopcast.domain.TflClient
import app.stopcast.domain.TflRateLimiter
import app.stopcast.domain.TflRequestPool
import app.stopcast.domain.cleanStopName
import app.stopcast.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher

/**
 * The production [TflClient]: Ktor over the OkHttp engine, decoding TfL's JSON
 * through content negotiation (SPEC *Architecture*; the HTTP-engine choice
 * mirrors clothescast). Off the render path — a surface renders the snapshot and
 * a refresh calls this in the background (SPEC *Snapshot-render*).
 *
 * [appKey] is read per request, not captured at construction, so a key the user pastes
 * in Settings takes effect on the next refresh without rebuilding the long-lived clients
 * (SPEC D7). A null/blank result means keyless access: stopcast ships no baked-in key, and
 * a user may supply their own for the higher rate limit. `expectSuccess` makes a non-2xx
 * (e.g. 429 rate-limited) throw, which the caller turns into the honest user-facing state
 * rather than a silent empty list.
 */
class KtorTflClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appKey: () -> String? = { null },
    // The shared TfL limiter (item 6), selected from the request's key snapshot: every request
    // acquires a token first, so a dense corner or a new caller throttles toward the budget instead
    // of firing a 429 storm. Taking the key means one read of the active key drives BOTH which
    // budget the request is charged to and the `app_key` it carries — a paste/clear can't leave a
    // request charged to one bucket but sent with the other key state (Codex). Defaults to the no-op
    // limiter so a test (or an unwired client) runs unthrottled; production passes the shared buckets
    // ([SharedTflRateLimiter.rateLimiterFor]).
    private val rateLimiterFor: (String?) -> TflRateLimiter = { TflRateLimiter.UNLIMITED },
    // The shared cap on in-flight requests, so a caller can fan a refresh out in parallel without
    // opening one connection per stop. Unbounded by default (tests, an unwired client); production
    // passes [SharedTflRequestPool.pool] so the app and widget share one cap.
    private val requestPool: TflRequestPool = TflRequestPool.UNBOUNDED,
    // Sink for recoverable response oddities (an unparseable disruption date), coarse facts only —
    // a stop id, never a coordinate or key (SPEC *Privacy*). No-op by default (tests, widget).
    private val warn: (String) -> Unit = {},
) : TflClient, StopFinder, RouteSequenceSource {
    override suspend fun arrivals(stopId: String): List<Departure> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$stopId/Arrivals") {
                applyAppKey(key)
            }.body<List<TflArrivalDto>>().map { it.toDeparture() }
        }

    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint") {
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("stopTypes", stopTypes.joinToString(","))
                parameter("radius", radiusMeters)
                // The geo search omits each stop's served lines unless asked; without this the
                // stops come back line-less in production (the fixture has them), so a suspended
                // no-prediction line couldn't surface as a status row without a second lookup.
                parameter("returnLines", true)
                applyAppKey(key)
            }.body<TflStopPointsResponseDto>().stopPoints.mapNotNull { it.toStopLocationOrNull() }
        }

    override suspend fun hubInfo(hubId: String): HubInfo =
        tflRequest { key ->
            val dto = httpClient.get("$baseUrl/StopPoint/$hubId") {
                applyAppKey(key)
            }.body<TflStopPointDto>()
            // The hub's own cleaned name titles the alert; the member-station spellings across the
            // tree are the alias set the disruption strip matches against (SPEC *Disruptions*).
            HubInfo(name = cleanStopName(dto.commonName), aliases = dto.hubStationNames())
        }

    override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
        tflRequest { key ->
            httpClient.get("$baseUrl/Line/$lineId/Route/Sequence/$direction") {
                applyAppKey(key)
            }.body<TflRouteSequenceDto>().toLineSequence()
        }

    override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
        // No lines → no request: a refresh with no predicted lines has nothing to check,
        // and an empty `/Line//Status` path would 404.
        if (lineIds.isEmpty()) return emptyList()
        val ids = lineIds.joinToString(",")
        return tflRequest { key ->
            httpClient.get("$baseUrl/Line/$ids/Status") {
                applyAppKey(key)
            }.body<List<TflLineDto>>().mapNotNull { it.toLineStatus() }
        }
    }

    override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$stopId/Disruption") {
                // Both default false, which would miss the very closures this exists to
                // surface (SPEC principle 1): getFamily includes disruptions recorded
                // against a station's child platforms/entrances (a hub id carries few of
                // its own), and includeRouteBlockedStops includes a stop blocked by a
                // route-level disruption.
                parameter("getFamily", true)
                parameter("includeRouteBlockedStops", true)
                applyAppKey(key)
            }.body<TflDisruptedPointFamilyDto>().allDisruptions { raw ->
                // Length only: the raw value is TfL's, but a bare fact keeps the log coarse.
                warn("stop disruption for stop $stopId: unparseable date (${raw.length} chars), window left open")
            }
        }

    override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
        if (stopIds.isEmpty()) return emptyMap()
        val ids = stopIds.joinToString(",")
        val entries = tflRequest { key ->
            // No getFamily: TfL rejects it for more than one stop, and a pole's family is its whole
            // junction, which would pin a sibling pole's closure on this one. A multi-stop request
            // returns a flat array, each entry naming its stop.
            httpClient.get("$baseUrl/StopPoint/$ids/Disruption") {
                parameter("includeRouteBlockedStops", true)
                applyAppKey(key)
            }.body<List<TflStopDisruptionDto>>()
        }
        val byStop = entries.groupBy { it.atcoCode }
        return stopIds.associateWith { stopId ->
            byStop[stopId].orEmpty().mapNotNull { dto ->
                dto.toStopDisruptionOrNull { raw ->
                    warn("stop disruption for stop $stopId: unparseable date (${raw.length} chars), window left open")
                }
            }.distinct()
        }
    }

    /**
     * Adds the request's `app_key` [key] when one is set (SPEC D7); a null/blank value adds nothing
     * (keyless). [key] is the single per-request snapshot [tflRequest] took, the same value the
     * limiter's budget was selected from — so the two never disagree.
     */
    private fun HttpRequestBuilder.applyAppKey(key: String?) {
        if (!key.isNullOrBlank()) parameter("app_key", key)
    }

    /**
     * Runs a TfL request and maps every transport/decode failure to the domain
     * [TflException] the caller reasons about (offline / rate-limited / unreachable),
     * so both endpoints share one error contract rather than repeating the mapping.
     * Sanitized throughout — a status code or class name, never a payload (SPEC *Privacy*).
     * Runs inside a [requestPool] slot, taken before the rate token so a queued request holds no
     * token while it waits for a slot.
     */
    private suspend fun <T> tflRequest(block: suspend (key: String?) -> T): T =
        requestPool.run { tflRequestInSlot(block) }

    private suspend inline fun <T> tflRequestInSlot(block: suspend (key: String?) -> T): T =
        try {
            // One read of the active key per request, used for both the budget and the app_key so
            // they can't disagree (Codex). Throttle toward the budget before issuing the request
            // (item 6): acquire() may suspend (deferring this background refresh) or throw
            // RateLimited when the budget is spent; both are handled below — RateLimited propagates
            // as the honest state, and a canceled wait rethrows CancellationException.
            val key = appKey()
            rateLimiterFor(key).acquire()
            block(key)
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
                engine {
                    config {
                        // OkHttp's own per-host cap defaults to 5, below the request pool's; raise it
                        // to match so the pool is the one limit that decides concurrency, not a
                        // hidden second queue inside the engine.
                        dispatcher(
                            Dispatcher().apply {
                                maxRequestsPerHost = SharedTflRequestPool.MAX_CONCURRENT
                            },
                        )
                    }
                }
                expectSuccess = true
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }
}
