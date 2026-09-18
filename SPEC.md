# Trackmo

Trackmo shows live London transport departures for the stops you care about, at a
glance — on the Android **lock screen**, on the home screen, and in full in the app.
It reads Transport for London's live prediction feed and answers one question fast:
*what's leaving the stops near me, and when?* — plus the disruptions (delays,
cancellations, stop and line closures) that would otherwise make those predictions a
lie.

Primary surface: an Android **lock-screen widget** on devices that support it —
Android 16 QPR onward, where the OS re-introduced widgets on the phone lock screen.
The same widget is an ordinary home-screen widget everywhere else, and the in-app
screen is the full view. See *One widget, many surfaces*.

**First deliverable is the in-app view, not the widget** (maintainer, 2026-09-18):
the departures screen is testable on an emulator and in Robolectric without wrestling
the lock-screen host, and it exercises the whole spine — the TfL client, the domain
logic, persistence — that the widget then renders from. The widget follows once that
spine is proven. `TODO.md` phases it this way.

Minimum supported version: Android 14 (API 34), the device floor across the sibling
fleet. The lock-screen *placement* needs Android 16 QPR; trackmo's widget is a
standard widget that becomes lock-screen-eligible where the OS allows it, so there is
no separate lock-screen code path.

Coverage is London / TfL only. Trackmo is not affiliated with Transport for London,
and uses the free, public TfL Unified API.

## Product behavior

Trackmo watches a small set of **stops** and, for each, shows the next few departures
and any disruption that would change whether you'd trust them. A "stop" is a TfL
`StopPoint`: a bus stop, an Underground/Overground/Elizabeth-line/DLR station, a tram
stop, or a pier.

### Watched stops

The user builds a short list of **watched stops** — the stops trackmo shows. A watched
stop can be narrowed to specific **lines** and/or a **direction** (inbound/outbound,
or a named platform), so a surface shows only the departures the user actually takes —
"Victoria line southbound at Warren Street", not every service through the station.
The default is all lines, both directions.

Watched stops are the source of truth for every surface, chosen ahead of time — see
decision **D1** for why the widget renders watched stops rather than "nearest to me".

### Finding stops

The app finds stops two ways:

- **Near me now** — with location permission, trackmo lists the stops nearest the
  user's current position (TfL `/StopPoint` by coordinates) so pinning the right ones
  is one tap. This is an in-app, on-demand action, never a background one.
- **Search** — by stop name or by line, for pinning a stop the user isn't standing at
  (home, work, the school run).

### Departures

For each watched stop, trackmo shows the next few departures: **line**, **destination**
(where the service is headed), **platform** where TfL gives one, and a **countdown**.
Countdowns render as minutes — "Due" when imminent, "3 min", "12 min" — sorted
soonest-first. The app shows more per stop; a glance surface (widget) shows the top
one or two per stop or per filtered line.

TfL's endpoint is named "Arrivals"; for a bus stop these are departures *from* that
stop, which is what a rider wants. Trackmo calls them departures throughout the UI.

### Disruptions

A departure time is worse than useless if the service is cancelled or the stop is
closed — showing the number alone is the "quietly wrong" failure (see *Engineering
quality bar*). So trackmo surfaces, for watched stops and their lines:

- **Line status** — minor/severe delays, part-suspended, suspended (TfL line status).
- **Stop closures and stop-level disruptions** — a closed entrance, a moved stop.
- **Cancellations** of specific predicted services, where TfL exposes them.

On a glance surface a disruption is a one-line summary plus a count ("Victoria line:
severe delays"); in the app it's the full text. A disrupted line/stop is marked even
when its predictions still look normal, because the prediction is the thing not to be
trusted — **and even when it has no predictions at all.** A suspended line often returns
zero arrivals, so trackmo retains the watched stop→line mapping independently of the
predictions and shows a line's status from that mapping; otherwise the surface would say
"no departures" for a suspended line and leave the user waiting for a service that isn't
coming — the quietly-wrong failure in its purest form.

Disruption and arrivals are separate requests, so a refresh can get one and not the
other. When the disruption lookup fails but arrivals succeed, trackmo does **not** present
those departures as verified-clean: it keeps the last-good disruption state (aged and
stamped like any other data) or marks the affected departures "status unknown", rather
than showing normal-looking times whose disruption status was never actually checked.

### Freshness

Live predictions go stale within about a minute, and no surface may present stale data
as if it were live (see **D4**). Every surface stamps what it shows with the age of the
fetch ("updated just now", "2 min ago") and recomputes each countdown from that fetch
time as the clock advances — so "3 min" becomes "1 min" between network calls without a
new request, and the numbers stay honest.

A prediction whose countdown reaches zero is **dropped from the list client-side** — it
is never held at "Due" or shown as negative time for a service that has already gone —
so the next departure advances between fetches without waiting for one.

"Too old to trust" is **one shared policy, not a per-surface judgment**: a single
staleness threshold, applied identically by every surface, beyond which countdowns are
withheld and the surface shows "tap to refresh" instead of numbers that are probably
wrong. The exact value is a tuned constant defined in one place in code (and pinned by
tests), not in this spec — but there is exactly one, so no two surfaces can disagree
about when data has gone stale.

- The **app** refreshes on open, on pull-to-refresh, and may auto-refresh while
  foregrounded.
- The **widget** refreshes opportunistically — on tap, on host update, and on a
  bounded periodic schedule while it is plausibly visible — and degrades to on-demand
  rather than polling hard in the background (**D5**). The spec's guarantee is honesty
  about staleness, not a fixed interval; the interval is a battery-tuning matter for
  `dev-docs`/`TODO.md`.

### When something is wrong

Trackmo never blanks or lies when it can't get fresh data. If TfL is unreachable, the
rate limit is hit, or location is denied, the surface says which ("offline", "can't
reach TfL", "location off") and shows the last good data stamped with its age, rather
than an empty box or unlabeled stale numbers.

## Architecture

- **Kotlin + Jetpack Compose**, a single `:app` module (mirroring simmo and Type
  Launcher), with all product logic in a pure-Kotlin **domain** layer (`app.trackmo.
  domain`) that is testable on the JVM with no Android: nearest-stop ranking,
  arrival→countdown formatting, disruption summarization, and staleness
  classification live there.
- The **TfL client** (Ktor/OkHttp + kotlinx.serialization models) sits behind a
  domain interface, so the decision logic is tested against recorded fixtures, not the
  live network.
- **Widget via Glance** (Compose-style widgets that emit RemoteViews), so home-screen
  and lock-screen placements are one implementation and share rendering vocabulary with
  the app's own composables where practical.
- **Persistence via DataStore**: watched stops, per-stop line/direction filters, the
  optional user API key, and the **last-good snapshot** each surface renders from.
- **Snapshot-render, warm at startup.** Every surface renders immediately and refreshes
  in the background — it never blocks its first frame on a network call *or a disk read*.
  The snapshot is warmed into memory at startup so the first frame is the real content;
  on a cold launch after process death, when the snapshot is still only on disk, the
  frame is a **stamped placeholder** shown at once and filled in when the async DataStore
  read completes, then refreshed. The widget in particular reads only the snapshot on its
  render path and kicks off a refresh; nothing on the draw path awaits a fetch.

## Data source, cost, and reliability

Trackmo's one external dependency is the **TfL Unified API** — free and public.

- Endpoints: `/StopPoint` (nearby by lat/lon + stop types + radius) and `/StopPoint/
  Search` for finding stops; `/StopPoint/{id}/Arrivals` for departures; `/StopPoint/
  {id}/Disruption`, `/Line/{ids}/Status` and `/Line/{ids}/Disruption` for disruptions.
- **Cost: £0.** Anonymous access is limited to ~50 requests/min; a free, user-supplied
  `app_key` raises it to ~500/min.
- **D7 — trackmo ships no baked-in key.** It works keyless out of the box, and a user
  may paste their own free `app_key` in settings for the higher limit. A shared, baked-in
  key would pool every user's traffic into one 500/min bucket and put a credential in
  the APK; a per-user key does neither.
- **Reliability:** one dependency, so if TfL is down or throttling, trackmo shows
  stamped last-good data and an offline/rate-limited notice (never a blank or an
  unlabeled stale number). Added latency lives off every render path (snapshot-render,
  above).

## One widget, many surfaces

There is one widget. On Android 16 QPR and later, where the OS re-added widgets to the
phone lock screen, it is eligible to sit there; everywhere else it is a home-screen
widget. Both use the standard AppWidget/Glance API — a lock-screen widget is just a
widget the host is allowed to place on the keyguard — so there is no lock-screen-specific
code path to maintain. Trackmo does **not** opt out of lock-screen placement (the
`not_keyguard` category). The app and its home-screen widget run on the fleet floor
(Android 14 / API 34); the lock-screen *placement* simply appears on devices new enough
to offer it.

## Privacy

Trackmo handles location and the set of stops the user watches — which together reveal
where they live, work, and travel. Trackmo itself sends none of it anywhere except the
TfL requests that *are* the product: a nearby-stops lookup necessarily sends coordinates
to TfL, and a departures lookup necessarily sends the watched stop IDs. That is inherent
and disclosed.

The persisted config (watched stops, the last-good snapshot, the user's `app_key`) does
travel through **Android's own backup and device-to-device transfer** — trackmo allows
both, deliberately, so a phone swap keeps the user's setup rather than losing it
(maintainer, 2026-09-18; the fleet's "never lose the user's work" over a literal
never-leaves-the-device wording). This is the platform's user-controlled channel tied to
the user's own Google account, not an off-device channel trackmo adds: cost £0, and no
Play Data Safety change (Android Auto Backup is a platform feature, not data trackmo
collects or transmits). The guarantee is therefore precise, not absolute — *trackmo*
adds no off-device channel beyond the TfL requests, and the user's own backup/transfer
carries their config under their control.

Nothing else leaves the device — no analytics over the user's stops or movements, and no
coordinate, stop list, or API key in logs, commits, PRs, or fixtures. The on-device
debug log carries coarse diagnostics only: a stop ID, a line id, an HTTP status — never
a raw coordinate or the user's API key.

## Engineering quality bar

In priority order; where a rule below conflicts with a principle, the principle wins.

1. **Never show a departure trackmo doesn't stand behind.** The worst outcome is the
   user missing a bus, or running for a cancelled one, because trackmo showed a number
   it shouldn't have trusted. Stale-but-unlabeled, or a normal-looking prediction for a
   suspended line, is worse than an honest "can't refresh" or "severe delays". Every
   surface is honest about age and disruption.
2. **Never fail silently.** If trackmo can't refresh — offline, rate-limited, location
   denied — it says so where the user is looking and shows stamped last-good data,
   rather than a blank or a silent stale render.
3. **Do the work ahead of time.** A surface renders from the persisted snapshot; the
   network is never on the render path. Warm at startup, cache, refresh in the
   background.
4. **Jank-free.** The app list and the widget render from in-memory/snapshot state; no
   I/O in composition, and no blocking a first frame on a fetch *or a disk read* — a
   stamped placeholder shows at once and fills in when the persisted snapshot loads.
5. **Battery is the user's cost.** Background refresh is bounded and degrades to
   on-demand; anything that adds a wakeup or a location request is a battery change and
   is justified as one.
6. **Say why.** Non-obvious decisions are recorded where the next reader needs them — a
   comment for a mechanism, the debug log for a refresh/disruption decision, the PR for
   a design trade-off.

New behavior is covered by a unit test; a bug fix adds a test that fails before and
passes after.

## Testing and distribution

Mirrors the sibling fleet:

- Product logic lives in the pure domain layer and is JVM-unit-tested against recorded
  TfL fixtures (never the live API in tests, and never a real coordinate in a fixture).
- Compose screens and Glance widget layouts get Robolectric + Roborazzi screenshot
  tests, wired into CI's screenshot allow-list.
- `./gradlew test` and `./gradlew lint` green before every push.
- CI mirrors the sibling `ci.yml` (build + unit tests + lint, a screenshot job, a
  Play-internal-track deploy job with release notes built from commit subjects) plus
  the shared `lanes`, `codex`, and `zizmor` checks.
- The on-device debug log is `mikelward/androidlog`, resolved as a published
  dependency.

## Non-goals

- **Journey planning / routing** (the TfL Journey API). Trackmo answers "what's next
  from here", not "how do I get there".
- **Non-TfL operators** outside the Unified API (National Rail services TfL doesn't
  carry, coach, etc.).
- **Ticketing**, Oyster/contactless balances, and service maps.
- **Writing to TfL.** Trackmo is read-only.
- **Continuous background location / geofencing.** Location is used on demand in the
  app to find nearby stops, never tracked in the background.

## Decision log

- **D1 — The widget renders watched stops; the app offers both watched and nearest.**
  The lock screen is a glance surface that must always have something to show without
  waiting on a location fix — and background location on the keyguard is restricted,
  often ungranted, and battery-costly. So what a surface shows is chosen ahead of time.
  The app still uses on-demand location to *find and suggest* nearby stops to pin, and
  to show a "near me now" list, keeping location off every refresh path.
- **D2 — A watched stop can be filtered to lines and/or a direction.** A station serves
  many lines and platforms; the rider takes one or two. Default is all.
- **D3 — Disruptions are surfaced alongside departures, and mark the line/stop even
  when predictions look normal.** A time for a cancelled or suspended service is the
  "quietly wrong" failure; the disruption is what makes the number trustworthy or not.
- **D4 — No surface presents stale data as live.** Data is stamped with its fetch age;
  countdowns recompute from the fetch time client-side; an expired prediction drops off
  the list rather than sticking at "Due"; and a single shared staleness threshold (one
  tuned constant, not a per-surface number) decides when numbers are withheld for "tap
  to refresh".
- **D5 — Widget refresh is opportunistic and bounded, not aggressive polling.** Tap,
  host update, and a bounded periodic schedule while plausibly visible; degrade to
  on-demand. The interval is a battery-tuning detail, not a spec guarantee.
- **D6 — The app refreshes on open and pull-to-refresh**, and may auto-refresh while
  foregrounded.
- **D7 — No baked-in TfL key; works keyless, optional user key for the higher limit.**
  A shared key would pool all users into one bucket and ship a credential; a per-user
  key avoids both. See *Data source*.
