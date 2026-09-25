package app.stopcast.data

import app.stopcast.domain.StarredRow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The rows a watch's complications are set to, as the watch syncs them back to the phone
 * (dev-docs/wear-os.md *Rows the watch depends on*): row identities only, keyed like stars, never
 * a departure or anything else. The phone keeps them in every envelope, after the starred rows.
 */
object WatchComplicationRows {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchStarKey.serializer())

    /** [rows] as the item's bytes, in a stable order, so an unchanged set writes an unchanged item. */
    fun encode(rows: Set<StarredRow>): ByteArray {
        val keys = rows.map(WatchStarKey::of).sortedWith(compareBy({ it.stopId }, { it.lineId }, { it.directionKey }))
        return json.encodeToString(serializer, keys).encodeToByteArray()
    }

    /** The rows in [bytes], or null when they can't be read (a newer or damaged item). */
    fun decode(bytes: ByteArray): Set<StarredRow>? = try {
        json.decodeFromString(serializer, bytes.decodeToString()).mapTo(LinkedHashSet()) { it.toDomain() }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
