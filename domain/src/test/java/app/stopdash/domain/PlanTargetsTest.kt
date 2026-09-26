package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Well-known public station ids as examples; example bus stop ids. */
class PlanTargetsTest {
    private fun station(id: String, @Suppress("UNUSED_PARAMETER") name: String) = PlanTargets.Member(id, listOf(LineRef("rail", "Rail", "national-rail")))

    private fun bus(id: String) = PlanTargets.Member(id, listOf(LineRef("1", "1", "bus"), LineRef("2", "2", "bus")))

    @Test
    fun `plans to each station code and one bus stop`() {
        val members = listOf(
            station("940GZZLUKSX", "King's Cross St. Pancras"),
            station("910GKGX", "London King's Cross"),
            station("910GSTPX", "St Pancras International"),
            station("910GSTPXBL", "St Pancras International LL"),
            bus("490000001A"),
            bus("490000001B"),
            station("HUBKGX", "King's Cross"),
        )
        assertEquals(listOf("940GZZLUKSX", "910GKGX", "910GSTPX", "910GSTPXBL", "490000001A"), PlanTargets.of(members))
    }

    @Test
    fun `stations sharing a prefix stay apart`() {
        val members = listOf(station("940GZZLUWHP", "West Hampstead"), station("910GWHMDSTD", "West Hampstead Thameslink"))
        assertEquals(listOf("940GZZLUWHP", "910GWHMDSTD"), PlanTargets.of(members))
    }

    @Test
    fun `stations shortened to one name stay apart`() {
        val members = listOf(station("940GZZLUHSC", "Hammersmith"), station("940GZZLUHSD", "Hammersmith"))
        assertEquals(listOf("940GZZLUHSC", "940GZZLUHSD"), PlanTargets.of(members))
    }

    @Test
    fun `a code extending another's is still its own station`() {
        val members = listOf(station("910GABWD", "Abbey Wood"), station("910GABWDXR", "Abbey Wood"))
        assertEquals(listOf("910GABWD", "910GABWDXR"), PlanTargets.of(members))
    }

    @Test
    fun `an ordinary station is one target`() {
        assertEquals(listOf("940GZZLUOXC"), PlanTargets.of(listOf(station("940GZZLUOXC", "Oxford Circus"))))
    }
}
