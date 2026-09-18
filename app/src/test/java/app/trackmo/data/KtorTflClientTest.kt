package app.trackmo.data

import app.trackmo.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "timeToStation": 60,
            "modeName": "bus"
          }
        ]
        """.trimIndent()

    // A recorded /Line/{ids}/Status fixture: victoria good, northern severely delayed,
    // central carrying both a good-service and a minor-delays entry (the disruption must
    // win). Public line names only (SPEC *Privacy*). Extra fields prove ignoreUnknownKeys.
    private val statusJson =
        """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.Line",
            "id": "victoria",
            "name": "Victoria",
            "modeName": "tube",
            "lineStatuses": [
              { "statusSeverity": 10, "statusSeverityDescription": "Good Service" }
            ]
          },
          {
            "id": "northern",
            "name": "Northern",
            "lineStatuses": [
              {
                "statusSeverity": 6,
                "statusSeverityDescription": "Severe Delays",
                "reason": "Northern line: severe delays while we fix a faulty train."
              }
            ]
          },
          {
            "id": "central",
            "name": "Central",
            "lineStatuses": [
              { "statusSeverity": 10, "statusSeverityDescription": "Good Service" },
              { "statusSeverity": 9, "statusSeverityDescription": "Minor Delays" }
            ]
          },
          {
            "id": "circle",
            "name": "Circle",
            "lineStatuses": []
          }
        ]
        """.trimIndent()

    // A recorded /StopPoint/{id}/Disruption fixture: a stop-level notice plus a
    // blank-description entry that must be dropped. Public station text only.
    private val disruptionJson =
        """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.Disruption",
            "atcoCode": "940GZZLUKSX",
            "description": "Station closed until further notice.",
            "closureText": "stationClosed"
          },
          { "description": "" }
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
        // modeName is carried so the UI can color by mode; absent → "".
        assertEquals("tube", departures[0].mode)
        assertEquals("", departures[1].mode)
        assertEquals("bus", departures[2].mode)
    }

    @Test
    fun `retains TfL direction, defaulting to empty when the field is absent`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        // TfL gives inbound/outbound on the tube predictions; the bus one omits it.
        assertEquals("inbound", departures[0].direction)
        assertEquals("outbound", departures[1].direction)
        assertEquals("", departures[2].direction)
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
    fun `parses line statuses, marking disruptions and leaving good service clean`() = runTest {
        val statuses = client(statusJson)
            .lineStatuses(listOf("victoria", "northern", "central", "circle"))
            .associateBy { it.lineId }

        // circle has no status entries → dropped as unknown, not fabricated into good
        // service (SPEC principle 1: don't manufacture a clean status from absent data).
        assertEquals(3, statuses.size)
        assertFalse(statuses.containsKey("circle"))
        assertFalse(statuses.getValue("victoria").disrupted)
        assertEquals("Good Service", statuses.getValue("victoria").description)
        assertTrue(statuses.getValue("northern").disrupted)
        assertEquals("Severe Delays", statuses.getValue("northern").description)
        // A good-service entry alongside a disruption must not mask it: filter to the
        // non-good statuses, then take the worst (lowest severity).
        assertTrue(statuses.getValue("central").disrupted)
        assertEquals("Minor Delays", statuses.getValue("central").description)
    }

    @Test
    fun `batches the requested lines into one Line Status request`() = runTest {
        var captured: HttpRequestData? = null
        client(statusJson, capture = { captured = it }).lineStatuses(listOf("victoria", "northern"))
        // One request, both lines in the path segment (segments decode any encoding).
        assertEquals(
            listOf("Line", "victoria,northern", "Status"),
            checkNotNull(captured).url.segments,
        )
    }

    @Test
    fun `no line ids makes no request and returns empty`() = runTest {
        var calls = 0
        val statuses = client(statusJson, capture = { calls++ }).lineStatuses(emptyList())
        assertEquals(0, calls)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `parses stop disruptions, dropping blank descriptions`() = runTest {
        val disruptions = client(disruptionJson).stopDisruptions("940GZZLUKSX")

        assertEquals(1, disruptions.size)
        assertEquals("Station closed until further notice.", disruptions[0].description)
    }

    @Test
    fun `requests the stop Disruption endpoint, including family and route-blocked stops`() = runTest {
        var captured: HttpRequestData? = null
        client(disruptionJson, capture = { captured = it }).stopDisruptions("940GZZLUKSX")
        val req = checkNotNull(captured)
        assertEquals("/StopPoint/940GZZLUKSX/Disruption", req.url.encodedPath)
        // Without these a closure recorded against a child platform/entrance, or a
        // route-blocked stop, would be missed (SPEC principle 1).
        assertEquals("true", req.url.parameters["getFamily"])
        assertEquals("true", req.url.parameters["includeRouteBlockedStops"])
    }

    @Test
    fun `a 429 maps to RateLimited, not an empty list`() {
        assertThrows(TflException.RateLimited::class.java) {
            runTest { client("{}", status = HttpStatusCode.TooManyRequests).arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `another non-2xx maps to Unreachable`() {
        assertThrows(TflException.Unreachable::class.java) {
            runTest { client("{}", status = HttpStatusCode.InternalServerError).arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `an unresolved host maps to Offline`() {
        // No DNS resolution is the device-is-offline signal.
        val client = throwingClient(UnknownHostException("api.tfl.example"))
        assertThrows(TflException.Offline::class.java) {
            runTest { client.arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `a transport failure while online maps to Unreachable, not Offline`() {
        // A read timeout (device online, TfL slow/down) is a subclass of IOException
        // but not UnknownHostException, so it's Unreachable — the UI must not tell an
        // online user they're offline during a TfL outage.
        val client = throwingClient(SocketTimeoutException("read timed out"))
        assertThrows(TflException.Unreachable::class.java) {
            runTest { client.arrivals("940GZZLUVIC") }
        }
    }

    private fun throwingClient(error: IOException): KtorTflClient {
        val engine = MockEngine { throw error }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example")
    }
}
