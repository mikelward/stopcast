package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public station names and example ids only. */
class FileActiveTripStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private val trip = ActiveTrip(
        route = TripRoute(
            listOf(
                TripLeg(
                    "tube", "jubilee", "Jubilee", "940GZZLUCWR", "Canada Water", "940GZZLUWLO", "Waterloo",
                    t0, t0.plusSeconds(600), path = listOf("940GZZLUBMY", "940GZZLUWLO"),
                    changeAfter = Duration.ofMinutes(3), headings = listOf("Stanmore"),
                ),
                TripLeg(TripLeg.WALKING, "", "", "940GZZLUWLO", "Waterloo", "910GWLOO", "Waterloo", t0.plusSeconds(600), t0.plusSeconds(900)),
            ),
        ),
        destinationName = "Waterloo",
        startedAt = t0,
        legIndex = 0,
        vehicleId = "162",
        boarded = true,
        dueOffAt = Instant.parse("2026-09-26T08:14:00Z"),
        warnedLeg = 0,
    )

    @Test
    fun `a started trip is kept across a reload, and forgotten when it ends`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        assertEquals(trip, FileActiveTripStore(file).load())
        FileActiveTripStore(file).save(null)
        assertNull(FileActiveTripStore(file).load())
        assertFalse(file.exists())
    }

    @Test
    fun `an unparseable file is no trip, and is deleted`() {
        val file = File(tmp.root, "active-trip.json").apply { writeText("not json") }
        val warnings = mutableListOf<String>()
        assertNull(FileActiveTripStore(file, warn = { warnings += it }).load())
        assertFalse(file.exists())
        assertTrue(warnings.single().startsWith("active trip unparseable"))
    }

    @Test
    fun `no file is no trip`() {
        assertNull(FileActiveTripStore(File(tmp.root, "none.json")).load())
    }
}
