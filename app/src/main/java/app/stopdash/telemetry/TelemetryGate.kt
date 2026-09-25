package app.stopdash.telemetry

import app.stopdash.StopdashDebugLog

/**
 * What the gate needs from the crash and analytics SDKs — a seam, so the consent logic is
 * JVM-testable without Firebase.
 *
 * The two SDKs keep separate persisted flags, and a kill can land between writing one and the
 * other. So Crashlytics' flag is the **commit marker**: [switchCollection] sets it last when
 * turning on and clears it first when turning off, and [collecting] reads only it. "Collecting"
 * therefore implies both SDKs are on; any half-done switch reads as not collecting.
 */
interface TelemetryBackend {
    /** Whether collection was fully switched on, as the SDKs persisted it (off on a fresh install). */
    val collecting: Boolean

    /** Turns both SDKs on or off, ordered as the class comment describes. */
    fun switchCollection(enabled: Boolean)

    /**
     * Whether crash reports captured this or an earlier run were waiting when the crash SDK
     * started — true, false, or null if it couldn't say. Fixed for the process: a deletion made
     * after startup shows only on a later launch.
     */
    fun checkUnsent(result: (Boolean?) -> Unit)

    /** Discards unsent crash reports and the analytics identity. Completes in the background. */
    fun discardUnsent()
}

/** A durable flag: the user opted in, but collection waits on a later launch (see [TelemetryGate]). */
interface PendingMarker {
    fun read(): Boolean

    fun save(pending: Boolean): Boolean
}

/** A marker that keeps nothing — for switching the SDKs off when the real store can't be opened. */
object NoPendingMarker : PendingMarker {
    override fun read() = false

    override fun save(pending: Boolean) = true
}

/**
 * Keeps crash reporting and usage analytics in step with the user's opt-in (SPEC *Privacy*). The
 * manifest starts both SDKs off; [TelemetryConsentHolder] applies the stored choice on every start
 * and each change. Every partial transition fails closed:
 *
 * - **Off** always switches both SDKs off, even when [TelemetryBackend.collecting] already reads
 *   off (a half-done earlier switch may have left Analytics on), clears any pending opt-in first, and
 *   discards whatever is unsent — Crashlytics keeps crashes on disk while off, so nothing captured
 *   while off or before a withdrawal goes out later.
 * - **On** from off never releases a crash captured before consent. Crashlytics keeps a crash on
 *   disk even while off, and offers no way to wait for a deletion to finish. So collection starts
 *   at once only if the crash SDK found **nothing waiting** at startup. If something was waiting,
 *   it's discarded now and the opt-in is marked **pending**: collection starts on a later launch
 *   whose own startup check comes back clean. An opt-out while pending wins.
 * - **On** when already collecting (an opted-in restart) just re-asserts both flags; the crash that
 *   ended the last run is sent.
 */
class TelemetryGate(private val backend: TelemetryBackend, private val pending: PendingMarker) {

    private val lock = Any()

    // The latest choice applied; an asynchronous opt-in completes only while it still holds.
    private var wanted = false

    /**
     * Called when an opt-in couldn't be kept — its pending marker failed to save — so the holder
     * can withdraw it rather than show a yes that the next start would silently reset.
     */
    var onOptInLost: () -> Unit = {}

    /** Whether the SDKs are collecting now, as they persisted it. */
    val collecting: Boolean get() = backend.collecting

    /** Whether an opt-in is waiting on a later launch to start collecting. */
    val pendingOptIn: Boolean get() = pending.read()

    /** Applies [optedIn] to the SDKs. Turning off is immediate; turning on may complete later. */
    fun apply(optedIn: Boolean) {
        synchronized(lock) {
            wanted = optedIn
            val wasCollecting = backend.collecting
            if (!optedIn) {
                // The pending opt-in goes first, so a switch-off that throws can't leave it behind
                // to revive the withdrawn yes on the next load.
                // Each step runs even if an earlier one throws; the first failure is rethrown after.
                attemptAll(
                    {
                        // A marker left behind could vouch for this withdrawn yes on the next load.
                        check(pending.save(false)) { "pending opt-in not cleared" }
                    },
                    { backend.switchCollection(false) },
                    // Always, not only if collection had been on: Crashlytics keeps a crash on disk
                    // while off, and nothing unsent should outlive a "no".
                    { backend.discardUnsent() },
                )
                return
            }
            if (wasCollecting) {
                backend.switchCollection(true)
                return
            }
        }
        backend.checkUnsent { waiting ->
            // This runs after apply() has returned, outside any caller's try: a throwing SDK or
            // marker here counts as a lost opt-in, which the holder withdraws (SDKs off, shown off).
            val kept = try {
                synchronized(lock) {
                    if (!wanted) return@synchronized true
                    when (waiting) {
                        false -> {
                            backend.discardUnsent() // the analytics identity from before
                            backend.switchCollection(true)
                            pending.save(false)
                            true
                        }
                        true -> {
                            backend.discardUnsent()
                            pending.save(true)
                        }
                        // Couldn't tell: stay off, and try again on the next launch.
                        null -> pending.save(true)
                    }
                }
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: completing the opt-in failed: %s", e::class.simpleName)
                false
            }
            if (!kept) onOptInLost()
        }
    }
}

/**
 * Runs every step, even when an earlier one throws, then rethrows the first failure (later ones
 * attached as suppressed). For switching telemetry off, where one SDK failing must never stop the
 * next from being switched off too.
 */
internal fun attemptAll(vararg steps: () -> Unit) {
    var failure: Exception? = null
    for (step in steps) {
        try {
            step()
        } catch (e: Exception) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
    }
    failure?.let { throw it }
}
