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

The only **user data** that leaves the device by any channel other than a TfL request is
carried by **your own Android backup and device-to-device transfer**, if you have it
enabled: like any app's data, your saved stopcast data (your settings and its last-good
departures snapshot) rides it, so a phone swap keeps your setup. That is Android's channel,
under your control and tied to your Google account — not something stopcast sends. So the
guarantee is precise rather than absolute: **the only user data stopcast itself sends off
the device goes in its TfL requests** (the Play update check carries none), and Android's
backup carries your saved data under your control.

## The on-device diagnostic log

StopCast keeps a diagnostic log on the device so a misbehaving routing or departure
decision can be explained — for example, why "couldn't get your location" appeared, or
why a line showed "couldn't check for disruptions". Diagnosing those needs a record of
what the app saw, so the log carries **coarse state and reasons**, and nothing more:

- a **stop ID** or a **line id** (TfL identifiers, e.g. `victoria`, `940GZZLUOXC`),
- an **HTTP status or failure reason** for a TfL request (e.g. `429`, `offline`),
- **location fix outcomes**: that a fix could not be obtained, whether a recent cached
  fix was used instead of a fresh one, and coarse timing — **never a coordinate**,
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
device connected). The logging is wired incrementally, one feature at a time: the
**location** fix outcomes land with the location-fallback change, and the
**departures/disruption** diagnostics with the disruption-logging change; until each is
wired, those warnings are discarded rather than recorded. A persisted, user-shareable
**bug-report export** is planned (`TODO.md` Phase 5). The on-device logging exception
above does **not** extend to an artifact that leaves the device: when the log is exported
to be shared, **travel data — stop IDs and line ids — is redacted**, because a shared file
is subject to the same rule as any other artifact that leaves the machine (`AGENTS.md`
*Privacy*). That redaction lands with the export feature itself.
