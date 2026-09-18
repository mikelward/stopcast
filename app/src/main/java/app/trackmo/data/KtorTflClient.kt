package app.trackmo.data

import app.trackmo.domain.Departure
import app.trackmo.domain.TflClient
import app.trackmo.domain.TflException
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
 * A blank [appKey] means keyless access (SPEC D7): trackmo ships no baked-in key,
 * and a user may supply their own for the higher rate limit. `expectSuccess` makes
 * a non-2xx (e.g. 429 rate-limited) throw, which the caller turns into the honest
 * user-facing state rather than a silent empty list.
 */
class KtorTflClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appKey: String? = null,
) : TflClient {
    override suspend fun arrivals(stopId: String): List<Departure> =
        try {
            httpClient.get("$baseUrl/StopPoint/$stopId/Arrivals") {
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body<List<TflArrivalDto>>().map { it.toDeparture() }
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
            // couldn't produce departures. Sanitized — the class name, no payload.
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
