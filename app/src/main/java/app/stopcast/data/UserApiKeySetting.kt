package app.stopcast.data

import app.stopcast.domain.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The user's TfL `app_key` in force right now, for every TfL request in the process (SPEC D7).
 *
 * The behavior lives in [UserApiKeyHolder] (scope-injected, so it's unit-testable); this object is
 * the process-wide singleton every caller reads. Held here, warmed from [AppSettings], because the
 * request clients are long-lived — a nearby ViewModel's client is built once and outlives a later
 * paste — so they read the key through this cache ([KtorTflClient]'s `appKey` provider is
 * `{ current }`) rather than capturing it at construction. That way a key pasted in Settings raises
 * the request budget on the very next refresh, and clearing it returns to keyless, with nothing
 * rebuilt.
 *
 * The value is a plain volatile, never a disk read on the caller's thread: requests run off the
 * render path already, and this keeps the read free. **The key is a credential**: it is sent only
 * with the user's own TfL requests (its purpose — the higher budget), held in memory here and in
 * the private settings file, and it rides Android backup like the rest of their settings. It is
 * never logged and never placed in any other off-device artifact (SPEC *Privacy*, `docs/PRIVACY.md`).
 */
object UserApiKeySetting {
    private val holder = UserApiKeyHolder(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The key applied to TfL requests right now, or null when keyless. See [UserApiKeyHolder]. */
    val current: String? get() = holder.current

    /** Begins reading the stored key into [current] and keeps it live for later writes. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** The user pasted [key] (or cleared it — null/blank). Applied at once, persisted in order. */
    fun set(key: String?) = holder.set(key)
}

/**
 * The process-wide app_key cache's behavior, with its [scope] injected so a test can drive it on a
 * virtual clock. One instance backs [UserApiKeySetting]; production passes a `Dispatchers.Default`
 * scope.
 */
class UserApiKeyHolder(
    private val scope: CoroutineScope,
    // Which stored key this holder keeps: the TfL app_key by default, or another credential
    // ([RailApiKeySetting]) handled the same way.
    private val read: (AppSettings) -> Flow<String?> = AppSettings::userApiKey,
    private val write: suspend (AppSettings, String?) -> Unit = { settings, key -> settings.setUserApiKey(key) },
) {

    private var settings: AppSettings = AppSettings.NONE
    private var collectJob: Job? = null
    private var writeJob: Job? = null

    /**
     * Writes are drained in send order by one consumer, so two quick edits — a paste then a Clear —
     * can't race to DataStore on independent coroutines and land out of order (the earlier value
     * written last, restored after process death). FIFO and unbounded.
     */
    private val writes = Channel<String?>(Channel.UNLIMITED)

    /**
     * Guards [current] and [userHasSet] so [set] (usually the main thread) and the store collector
     * (a background coroutine) can't interleave into a check-then-write race — an earlier version
     * gated the collector on a counter, but reading the counter and assigning [current] weren't
     * atomic, so the collector could still restore a stale value between a user's change and its
     * write (Codex P2). Both critical sections are two field writes with no suspension, so the
     * monitor is held only briefly.
     */
    private val lock = Any()

    /**
     * Whether the user has set the key in this process. This holder is the process's sole writer of
     * the key, so every store emission after the warm read is an echo of one of our own writes —
     * once the user has made a choice, [current] is authoritative and those echoes must not roll it
     * back (that is exactly how a cleared key could otherwise be restored and then sent). Before the
     * first [set], the warm read seeds [current] from the store.
     */
    private var userHasSet = false

    /**
     * The key applied to TfL requests right now, or null when keyless (the default until [warm]
     * reads the stored value). Volatile so a background request coroutine sees the latest paste
     * without synchronization.
     */
    @Volatile
    var current: String? = null
        private set(value) {
            field = value
            _changes.value = value
        }

    private val _changes = MutableStateFlow<String?>(null)

    /** [current] as a flow, so a screen can act when the key changes. */
    val changes: StateFlow<String?> = _changes.asStateFlow()

    /**
     * Begins reading the stored key into [current], off the main thread, and keeps it live for
     * later writes. Idempotent — a second call is ignored — so it can be called from
     * `StopcastApp.onCreate` without stacking collectors. Requests before the warm read lands run
     * keyless (the safe default: a real key only raises the budget, never gates a fetch).
     */
    fun warm(appSettings: AppSettings) {
        settings = appSettings
        if (writeJob == null) {
            writeJob = scope.launch {
                for (key in writes) {
                    try {
                        write(settings, key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort: the value is already applied in memory, so a failed write
                        // leaves the app using the chosen key until a restart re-reads the store.
                        // Sanitized — the failure class only, never the key itself (SPEC *Privacy*).
                        logAppSettingsWarning("api key write failed: ${e::class.simpleName}")
                    }
                }
            }
        }
        if (collectJob != null) return
        collectJob = scope.launch {
            read(appSettings).collect { stored ->
                // Seed from the store only until the user makes their own change; after that
                // [current] is theirs and store echoes of our writes must not roll it back. Under
                // the lock so this decision is atomic with a concurrent [set].
                synchronized(lock) {
                    if (!userHasSet) current = stored
                }
            }
        }
    }

    /**
     * The user pasted [key] (or cleared it — null/blank). Applied to [current] at once so the next
     * request uses it, and persisted in order in the background. Blank normalizes to null (keyless).
     */
    fun set(key: String?) {
        val normalized = key?.trim()?.takeIf(String::isNotEmpty)
        synchronized(lock) {
            userHasSet = true
            current = normalized
        }
        writes.trySend(normalized)
    }
}

/**
 * The user's Rail Data Marketplace key in force right now, for National Rail's live departures
 * (SPEC *National Rail*): the same process-wide cache as [UserApiKeySetting], over its own stored
 * setting. Null means no key, and then no National Rail request is made. **A credential**: sent only
 * with the user's own National Rail requests, never logged or placed in any other off-device
 * artifact (`docs/PRIVACY.md`).
 */
object RailApiKeySetting {
    private val holder = UserApiKeyHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        read = AppSettings::railApiKey,
        write = { settings, key -> settings.setRailApiKey(key) },
    )

    /** The key applied to National Rail requests right now, or null. */
    val current: String? get() = holder.current

    /** [current] as a flow: a departures list refetches when it changes. */
    val changes: StateFlow<String?> get() = holder.changes

    /** Begins reading the stored key into [current]. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** The user pasted [key] (or cleared it). Applied at once, persisted in order. */
    fun set(key: String?) = holder.set(key)
}
