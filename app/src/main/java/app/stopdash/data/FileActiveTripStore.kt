package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The trip on the way (SPEC *On the way*), in one JSON [file] in the app's no-backup directory: it
 * survives the app being closed but not a device transfer, and never leaves the device — where a
 * rider is going is theirs (`docs/PRIVACY.md`). Blocking; call it off the main thread. An unreadable
 * or unparseable file loads as no trip and is deleted; a failed write is logged (a bare reason, never
 * a stop) and the trip is followed in memory only.
 */
internal class FileActiveTripStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun load(): ActiveTrip? {
        if (tmp.exists() && !tmp.delete()) warn("active trip: stale temp file not deleted")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<PersistedActiveTrip>(file.readText()).toTrip()
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: IllegalArgumentException) {
            // A time or duration that doesn't parse.
            discard("unparseable", e)
        } catch (e: DateTimeParseException) {
            discard("unparseable", e)
        }
    }

    /** Keep [trip], or forget the kept one when null (the trip ended). */
    @Synchronized
    fun save(trip: ActiveTrip?) {
        if (trip == null) {
            if (file.exists() && !file.delete()) warn("active trip not forgotten: delete failed")
            return
        }
        try {
            tmp.writeText(json.encodeToString(PersistedActiveTrip.of(trip)))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("active trip not saved: rename failed")
        } catch (e: IOException) {
            warn("active trip not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun discard(why: String, e: Exception): ActiveTrip? {
        val deleted = file.delete()
        warn("active trip $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedActiveTrip(
    val legs: List<PersistedTripLeg>,
    val destinationName: String,
    val startedAt: String,
    val legIndex: Int,
    val legStartedAt: String,
    val vehicleId: String = "",
    val boarded: Boolean = false,
    val dueOffAt: String? = null,
    val warnedLeg: Int = -1,
) {
    fun toTrip() = ActiveTrip(
        route = TripRoute(legs.map { it.toLeg() }),
        destinationName = destinationName,
        startedAt = Instant.parse(startedAt),
        legIndex = legIndex,
        legStartedAt = Instant.parse(legStartedAt),
        vehicleId = vehicleId,
        boarded = boarded,
        dueOffAt = dueOffAt?.let(Instant::parse),
        warnedLeg = warnedLeg,
    )

    companion object {
        fun of(trip: ActiveTrip) = PersistedActiveTrip(
            legs = trip.route.legs.map { PersistedTripLeg.of(it) },
            destinationName = trip.destinationName,
            startedAt = trip.startedAt.toString(),
            legIndex = trip.legIndex,
            legStartedAt = trip.legStartedAt.toString(),
            vehicleId = trip.vehicleId,
            boarded = trip.boarded,
            dueOffAt = trip.dueOffAt?.toString(),
            warnedLeg = trip.warnedLeg,
        )
    }
}

@Serializable
private data class PersistedTripLeg(
    val mode: String,
    val lineId: String,
    val lineName: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val departure: String,
    val arrival: String,
    val path: List<String> = emptyList(),
    val changeAfterSeconds: Long = 0,
    val headings: List<String> = emptyList(),
    val fromArea: String = "",
    val toArea: String = "",
) {
    fun toLeg() = TripLeg(
        mode, lineId, lineName, fromId, fromName, toId, toName, Instant.parse(departure), Instant.parse(arrival),
        path, Duration.ofSeconds(changeAfterSeconds), headings, fromArea, toArea,
    )

    companion object {
        fun of(leg: TripLeg) = PersistedTripLeg(
            leg.mode, leg.lineId, leg.lineName, leg.fromId, leg.fromName, leg.toId, leg.toName,
            leg.departure.toString(), leg.arrival.toString(), leg.path, leg.changeAfter.seconds, leg.headings,
            leg.fromArea, leg.toArea,
        )
    }
}
