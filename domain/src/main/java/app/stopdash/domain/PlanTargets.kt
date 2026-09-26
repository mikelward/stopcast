package app.stopdash.domain

/**
 * Where a trip to a station complex is planned to (SPEC *Trips with a change*). The Journey Planner
 * takes one stop per end and leans toward that stop's own mode, so a trip to an interchange such
 * as King's Cross St. Pancras is planned once to each of its distinct stations and once to its
 * bus stops, and the answers are merged: the best way there whatever the line or mode.
 */
object PlanTargets {
    /**
     * One member stop of a complex: its [id] and the [lines] it serves. It's a bus stop when every
     * line it serves is a bus: those are planned to once for them all.
     */
    data class Member(val id: String, val lines: List<LineRef>) {
        val bus: Boolean get() = lines.isNotEmpty() && lines.all { it.mode.equals(BUS, ignoreCase = true) }
    }

    /**
     * The stop ids to plan to among [members], in their order: each station code, and
     * one bus stop for all the bus stops (the Planner walks between them). An interchange's own id
     * ("HUB…") is never one: the Planner can't plan to it. Station codes are never merged: neither
     * names (shortened for display, so Hammersmith's two stations share one) nor ids (Abbey Wood's
     * Elizabeth line code extends its National Rail one) tell a platform variant from another
     * station, and a code planned to twice costs a call where two stations merged would skip one.
     */
    fun of(members: List<Member>): List<String> {
        val stations = mutableListOf<Member>()
        var bus: Member? = null
        for (member in members) {
            if (member.id.isBlank() || member.id.startsWith(HUB_PREFIX)) continue
            if (member.bus) {
                if (bus == null) bus = member
            } else if (stations.none { it.id == member.id }) {
                stations += member
            }
        }
        return stations.map { it.id } + listOfNotNull(bus?.id)
    }

    private const val HUB_PREFIX = "HUB"
    private const val BUS = "bus"
}
