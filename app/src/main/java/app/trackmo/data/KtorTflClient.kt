package app.trackmo.data

import app.trackmo.domain.Departure
import app.trackmo.domain.TflClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
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
    override suspend fun arrivals(stopId: String): List<Departure> {
        val predictions: List<TflArrivalDto> =
            httpClient.get("$baseUrl/StopPoint/$stopId/Arrivals") {
                if (!appKey.isNullOrBlank()) parameter("app_key", appKey)
            }.body()
        return predictions.map { it.toDeparture() }
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
