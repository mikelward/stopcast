package app.stopdash.domain

/**
 * Whether a stop notice says the stop itself is closed (SPEC *Disruptions*). TfL's stop notices mix
 * whole-stop closures ("Bus Stop Closed", "Station closed due to strike action") with narrower ones
 * (a lift out of order, one entrance shut, a moved pole), and nothing in the data tells them apart.
 * So only the notice's own wording counts: "stop" or "station" followed by "closed" (optionally "is"
 * or "will be" between), which a lift, an entrance or a platform notice doesn't say. Anything else
 * stays unclaimed — a card never says "Closed" that the notice doesn't.
 */
object ClosedNotice {
    private val SAYS_CLOSED = Regex("""\b(stop|station)\s+(is\s+|will\s+be\s+)?closed\b""", RegexOption.IGNORE_CASE)

    fun saysClosed(text: String): Boolean = SAYS_CLOSED.containsMatchIn(text)
}
