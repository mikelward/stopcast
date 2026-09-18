package app.trackmo.domain

/**
 * A stop's identity and position — enough to rank it by distance for the in-app
 * "near me now" list (SPEC *Finding stops*). Coordinates are the user's business:
 * they stay in memory for the on-demand ranking and never reach a log or any
 * artifact that leaves the device (SPEC *Privacy*).
 */
data class StopLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
