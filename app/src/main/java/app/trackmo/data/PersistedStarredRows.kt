package app.trackmo.data

import app.trackmo.domain.StarredRow
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the starred set, kept in the `data` layer so the domain types stay
 * free of serialization annotations (mirrors [PersistedWatchedStops]). `version` lets a future
 * format change be detected and discarded rather than mis-read; an unknown version reads as
 * "nothing starred" (an empty set), which fails safe — the user re-stars rather than the app
 * mis-reading old bytes.
 *
 * A private persistence detail — the app opens no off-device channel of its own for it — but
 * the file is not strictly device-local: it rides Android backup and device-to-device transfer
 * like the rest of the app's config (SPEC §12 / *Privacy*), a platform path the user controls.
 * Each entry is the three-part row identity only (stop, line, resolved direction key), no
 * coordinate and no display text — a star is ranking, not membership (SPEC D8).
 */
@Serializable
internal data class PersistedStarredRows(
    val version: Int = CURRENT_VERSION,
    val rows: List<PersistedStarredRow> = emptyList(),
) {
    companion object {
        /** The current on-disk format. Bump when a field's meaning changes incompatibly. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PersistedStarredRow(
    val stopId: String,
    val lineId: String,
    val directionKey: String,
)

internal fun Set<StarredRow>.toPersisted(): PersistedStarredRows =
    PersistedStarredRows(rows = map { PersistedStarredRow(it.stopId, it.lineId, it.directionKey) })

/**
 * The domain set, or null when the stored format is a version this build doesn't know — the
 * caller then reads it as [app.trackmo.domain.StarredRowSet.Unavailable] and preserves the file
 * rather than overwriting it (see [DataStoreStarredRowsStore]).
 *
 * Like [PersistedWatchedStops.toDomain], this covers a newer version that still **decodes**
 * (extra fields ignored), not a future schema that changes a field's *shape* so the payload
 * fails to decode at all — that throws in [StarredRowsSerializer], reported as corruption. With
 * v1 the only schema, a decode failure is genuine corruption; a future incompatible bump MUST
 * first add a version-envelope read (tracked in `TODO.md`, shared with the watched-stops store).
 */
internal fun PersistedStarredRows.toDomain(): Set<StarredRow>? {
    if (version != PersistedStarredRows.CURRENT_VERSION) return null
    return rows.mapTo(mutableSetOf()) { StarredRow(it.stopId, it.lineId, it.directionKey) }
}
