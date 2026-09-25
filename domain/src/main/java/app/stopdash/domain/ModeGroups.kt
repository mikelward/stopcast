package app.stopdash.domain

/**
 * The groups a rider hides modes by (SPEC *Finding stops → Hiding a mode*, maintainer 2026-09-24):
 * TfL's mode ids folded into what a rider calls them. The DLR rides with the Tube (turn up and go,
 * on the Tube map); the Overground, the Elizabeth line and National Rail are all "Train". Hiding a
 * group hides every mode in it; a mode no group names (the cable car) is a group of its own.
 */
object ModeGroups {
    /** One group: its stable [key] and the TfL mode ids it holds. */
    data class Group(val key: String, val modes: Set<String>)

    /** The groups the near-me search can return a stop for, in the order the menu lists them. */
    val ALL: List<Group> = listOf(
        Group("tube", setOf("tube", "dlr")),
        Group("train", setOf("overground", "elizabeth-line", "national-rail")),
        Group("bus", setOf("bus")),
        Group("tram", setOf("tram")),
        Group("boat", setOf("river-bus")),
        Group("coach", setOf("coach")),
    )

    /** The group [mode] belongs to; an unknown mode is its own group. */
    fun of(mode: String): Group =
        ALL.firstOrNull { g -> g.modes.any { it.equals(mode, ignoreCase = true) } }
            ?: Group(mode.lowercase(), setOf(mode.lowercase()))

    /**
     * Whether [group] is hidden (its checkbox reads unticked). Groups are only ever hidden or shown
     * whole, so any member hidden means the group is.
     */
    fun isHidden(group: Group, hidden: Set<String>): Boolean = group.modes.any { HiddenModes.isHidden(it, hidden) }

    /** The groups with any mode hidden, in menu order, for the banner that names them. */
    fun hiddenGroups(hidden: Set<String>): List<Group> =
        (ALL + hidden.map(::of)).distinctBy { it.key }.filter { g -> g.modes.any { HiddenModes.isHidden(it, hidden) } }

    /** [hidden] with all of [group] hidden (or shown again when [hide] is false). */
    fun withGroup(hidden: Set<String>, group: Group, hide: Boolean): Set<String> =
        if (hide) {
            hidden + group.modes
        } else {
            hidden.filterNotTo(LinkedHashSet()) { m -> group.modes.any { it.equals(m, ignoreCase = true) } }
        }
}
