# StopCast — privacy

This describes what stopcast keeps, what leaves the device, and — in detail — what its
on-device diagnostic log carries. It is the disclosure `AGENTS.md` requires to exist
before any on-device logging ships. The full store-facing Play Data Safety statement is
finalized at release (see `TODO.md` Phase 5); this document is the engineering-level
truth those answers are built from.

## What leaves the device

StopCast is a **client-only** app. It makes network calls to just two places:
**Transport for London's Unified API**, the calls that *are* the product, and — on a release
build only — **Google Play**, to ask whether an app update is available (detailed below; it
carries nothing about you). Everything stopcast sends that says anything about **you** goes
only to TfL, and only ever what a request needs to answer your question about departures: the
details of what you're looking up (your location for "near me
now" — **precise** if you grant precise and a precise fix is available, otherwise approximate
(if you grant only approximate, or if no precise fix can be obtained) — or the
stop or line you're after) and, if you've set an optional TfL API key
(`app_key`), that key as your own credential, sent with your own TfL calls and nowhere
else. Location is used **only on demand**, never in the background.

Nothing else leaves the device *to stopcast*: no analytics, no crash reporter, no
third-party tracker, and no server of stopcast's own.

On a release build, stopcast makes **one** other kind of network call — to **Google
Play**, asking whether an app update is available (this drives the "update available" dot
on the menu). It is a Play Services query about the app's *own* version; it sends **no**
location, watched stops, API key, or any other user data — nothing about you or your
travel — so it adds no new Play Data Safety category beyond Google Play's existing role as
the app's distributor. It is free, runs release-only (a debug build isn't a Play app), and
silently does nothing if Play is unavailable.

Two channels other than a TfL request can carry **user data** off the device, and both are
under your control rather than stopcast's. The first is **your own Android backup and
device-to-device transfer**, if you have it enabled: like any app's data, your saved stopcast
data (your settings and its last-good departures snapshot) rides it, so a phone swap keeps your
setup. That is Android's channel, tied to your Google account — not something stopcast sends.
The second is a **bug report you choose to send** (see *Sending a bug report* below): it hands
the app you pick a diagnostic report that, unlike everything else here, **includes your exact
location and a screenshot of the screen you sent it from** — but only after a consent screen
that says so, and then to your clipboard and the app you pick (the clipboard copy happens as
soon as you confirm — detailed below).
So the guarantee is precise rather than absolute: **the only user data stopcast itself sends
off the device goes in its TfL requests** (the Play update check carries none); Android's backup
carries your saved data under your control, and a bug report carries what you consent to share.

**Find a station** sends the name you type to TfL's stop search, once you pause typing, and
then the chosen station's id to look up its stops and departures. The name isn't saved,
logged or sent anywhere else, and the station you look at isn't remembered once you leave it.

**Starred journeys** (two stops you travel between) are kept on the device with your other
settings and stars, so they ride your own Android backup like the rest (above); they are never
logged or sent anywhere. For the widget, the departures at a journey's nearer stop, and which of
them reach the other end, are saved with the widget's other departures on the device. Showing a journey's trains fetches the departures at its nearer stop
from TfL, like any other stop.

**Kept on the device, never backed up:** to skip a repeat stop lookup when you reopen the app
near where you last used it, stopcast keeps the **positions of its last few nearby-stop lookups**
(up to four places) and the stops found around each, for up to a day, in the app's cache
directory. Android never includes that directory in a backup or device transfer, it is never
logged or sent anywhere, and clearing the app's cache removes it; an entry older than a day is
deleted the next time the app looks up nearby stops.

## The on-device diagnostic log

StopCast keeps a diagnostic log on the device so a misbehaving routing or departure
decision can be explained — for example, why "couldn't get your location" appeared, or
why a line showed "couldn't check for disruptions". Diagnosing those needs a record of
what the app saw, so the log carries **coarse state and reasons**, and nothing more:

- a **stop ID** or a **line id** (TfL identifiers, e.g. `victoria`, `940GZZLUOXC`),
- an **HTTP status or failure reason** for a TfL request (e.g. `429`, `offline`),
- **location fix outcomes**: that a fix could not be obtained, whether a recent cached
  fix was used instead of a fresh one, and coarse timing; for each fix the app uses, **which
  location provider** supplied it (e.g. `network`, `gps`), the **accuracy radius** that provider
  reported (or "unknown"), and **how old** it was — **never a coordinate**,
- **per-refresh request counts and timing**: how many TfL requests a refresh made, of which
  kinds, how long it took, and how long it waited on the app's own rate limit — counts and
  milliseconds only, no stop or place,
- **which disruption/status lookup was unknown and why** (e.g. a line TfL returned no
  status for, or a prediction with no line id to check),
- a **failed Play update check, or a failed attempt to open the Play listing** (release
  builds only — see *What leaves the device*): the caught exception's class name (e.g.
  `IllegalStateException`), or a fixed "no app to open the Play listing" reason — never any
  Play account, device, or version detail.

The log **never** carries:

- a **raw coordinate** or a full address,
- the TfL **`app_key`**,
- any contact, name, or other personal identifier.

These diagnostics are written to Android's **Logcat** (visible to a developer with the
device connected) **and to a persisted log file on the device** — a small rotating file in
the app's private cache (excluded from backup), kept so that a crash or a silent process
kill still leaves a record of the last thing the app saw. The persisted log **stays on the
device**: stopcast registers no off-device destination for it, so nothing here is sent
anywhere. (A Crashlytics-style crash/breadcrumb reporter is a possible future addition — a
new off-device channel that would be disclosed here, and in the Play Data Safety answers,
before it ships.) The logging runs through one shared on-device buffer
(`mikelward/androidlog`), wired incrementally: a feature whose warning seam isn't connected
yet is discarded rather than recorded.

Two ways to get the log **off** the device for a bug report are foreseen, and they draw the
privacy line differently. A **location-safe export** — the log shared with its **travel data
(stop IDs and line ids) redacted**, since a shared file is otherwise subject to the same rule
as any other artifact that leaves the machine (`AGENTS.md` *Privacy*) — is still planned
(`TODO.md`). The other is the **consent-gated bug report** described next, which does the
opposite on purpose: it keeps the location *in*, openly and under consent, because that is the
context a routing bug is diagnosed from.

## Sending a bug report

StopCast can send a **bug report** from its overflow menu. This is the one channel that
deliberately carries what the on-device log never does — so it is gated by an explicit consent
screen that names exactly what leaves, and nothing is assembled or sent until you pass it. The
report carries:

- the **diagnostic log** described above (in full, not redacted — the report already reveals
  more than the log's stop/line ids would),
- a **screenshot of the screen you sent the report from** — the departures or error screen you
  are reporting, so the report shows what you saw; it is the app's own window, so the consent
  dialog itself is not in it,
- your **exact location from the last nearby lookup** — the fix that found the stops you were
  looking at, which is where a routing bug happened; it is labeled that way in the report, since
  the departures screen can stay open while you move, so it is not necessarily where you are the
  instant you send,
- **how far you are from each nearby stop**.

It is **user-initiated and £0**: stopcast runs no service of its own for it. Tapping *Send bug
report* opens the consent screen; on *Continue* the report is **copied to your clipboard**
(on-device, but readable by other apps from then) **and** handed to Android's share sheet, where
**you** choose the app it goes to — an email, an issue, a chat. So the clipboard copy happens as
soon as you tap Continue; nothing is sent to a destination *you* pick until you pick it, but the
report has left the composing screen at that point. A **"don't ask again"** option skips the
consent screen on later reports; it never sends anything on its own.

This is an honest trade, not a location-safe one: a report useful for a *where did routing go
wrong* bug has to say where you were, so this one says so plainly rather than stripping the
context to look safe. For **Play Data Safety** it is a user-initiated share of app diagnostics,
a screenshot, and a coarse-or-precise location to an app you choose — disclosed here as such.
