package app.stopdash.domain

/**
 * Why a [TflClient] call failed, in the terms a surface must tell apart to be honest
 * (SPEC principles 1–2: a failure is shown as what it is, never an empty or stale
 * list). The client maps transport and HTTP failures onto these, so the caller — the
 * ViewModel now, the widget later — decides what the user sees without depending on
 * the HTTP engine. Messages stay sanitized (a coarse reason or an HTTP status, never a
 * stop id, coordinate, or `app_key`; SPEC *Privacy* applies to logs and errors too).
 */
sealed class TflException(message: String, cause: Throwable?) : Exception(message, cause) {
    /** No usable network — offline, or TfL's host didn't resolve or connect. */
    class Offline(cause: Throwable?) : TflException("offline", cause)

    /** TfL returned 429 — a user-supplied `app_key` raises the limit (SPEC D7). */
    class RateLimited(cause: Throwable?) : TflException("rate limited", cause)

    /** Reached TfL but the request still failed — a non-2xx, or a decode failure. */
    class Unreachable(reason: String, cause: Throwable?) : TflException(reason, cause)

    /**
     * Online, but the request didn't complete — a timeout, a refused or reset connection, a TLS
     * failure. Told apart from [Unreachable] so a surface says "network error" rather than blame
     * TfL for what may be the phone's own connection.
     */
    class Network(reason: String, cause: Throwable?) : TflException(reason, cause)
}
