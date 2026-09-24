package app.stopcast.data

/**
 * The names both apps agree on for the phone-to-watch sync (dev-docs/wear-os.md), kept in one
 * place so neither side can drift from the other.
 */
object WatchSyncContract {
    /** The `DataItem` path the phone writes the latest [WatchEnvelope] to. */
    const val SNAPSHOT_PATH = "/stopcast/snapshot"

    /** The envelope's key in that item: a byte array, or an `Asset` when it's too big for one. */
    const val ENVELOPE_KEY = "envelope"

    /** The phone's write generation, different on every write. It makes every write a change, so
     *  a forced republish of the same envelope still reaches the watch as a new `DATA_CHANGED`
     *  event. It carries no ordering: the phone serializes its writes, and the Data Layer delivers
     *  them to the watch in order. */
    const val GENERATION_KEY = "generation"

    /** Declared by the watch app (res/values/wear.xml), so the phone publishes only when a paired
     *  watch has it installed. */
    const val WATCH_CAPABILITY = "stopcast_watch"
}
