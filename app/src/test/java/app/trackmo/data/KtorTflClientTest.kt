package app.trackmo.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorTflClientTest {
    // A recorded /Arrivals fixture: public infrastructure/line names only, no user
    // data (SPEC *Privacy*). Prediction 1 carries extra TfL fields to prove
    // ignoreUnknownKeys; prediction 2 has a blank platform and blank
    // destinationName (falls back to `towards`).
    private val arrivalsJson =
        """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.Prediction",
            "id": "111",
            "naptanId": "940GZZLUVIC",
            "stationName": "Victoria Underground Station",
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "Northbound - Platform 1",
            "direction": "inbound",
            "destinationName": "Walthamstow Central",
            "towards": "Walthamstow Central",
            "expectedArrival": "2026-09-18T08:03:00Z",
            "timeToStation": 180,
            "modeName": "tube"
          },
          {
            "id": "222",
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "",
            "direction": "outbound",
            "destinationName": "",
            "towards": "Brixton",
            "expectedArrival": "2026-09-18T08:05:30Z",
            "timeToStation": 330
          },
          {
            "id": "333",
            "lineId": "24",
            "lineName": "24",
            "platformName": "",
            "destinationName": "Pimlico",
            "towards": "Pimlico, Grosvenor Road",
            "expectedArrival": "2026-09-18T08:01:00Z",
            "timeToStation": 60
          }
        ]
        """.trimIndent()

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        appKey: String? = null,
        capture: (HttpRequestData) -> Unit = {},
    ): KtorTflClient {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", appKey = appKey)
    }

    @Test
    fun `parses and maps predictions, tolerating unknown fields`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        assertEquals(3, departures.size)
        // Order is preserved from the response; sorting is the caller's job (Countdown).
        assertEquals("Victoria", departures[0].lineName)
        assertEquals("Walthamstow Central", departures[0].destination)
        assertEquals("Northbound - Platform 1", departures[0].platform)
        assertEquals(Instant.parse("2026-09-18T08:03:00Z"), departures[0].expectedArrival)
    }

    @Test
    fun `blank platform is null and blank destination falls back to towards`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        assertNull(departures[1].platform)
        assertEquals("Brixton", departures[1].destination)
        assertNull(departures[2].platform)
        assertEquals("Pimlico", departures[2].destination)
    }

    @Test
    fun `requests the Arrivals endpoint and adds app_key only when set`() = runTest {
        var captured: HttpRequestData? = null
        client(arrivalsJson, capture = { captured = it }).arrivals("940GZZLUVIC")
        val keyless = checkNotNull(captured)
        assertEquals("/StopPoint/940GZZLUVIC/Arrivals", keyless.url.encodedPath)
        assertNull(keyless.url.parameters["app_key"])

        var capturedKeyed: HttpRequestData? = null
        client(arrivalsJson, appKey = "EXAMPLE", capture = { capturedKeyed = it }).arrivals("940GZZLUVIC")
        assertEquals("EXAMPLE", checkNotNull(capturedKeyed).url.parameters["app_key"])
    }

    @Test
    fun `a rate-limited response throws rather than returning empty`() {
        assertThrows(ClientRequestException::class.java) {
            runTest { client("{}", status = HttpStatusCode.TooManyRequests).arrivals("940GZZLUVIC") }
        }
    }
}
