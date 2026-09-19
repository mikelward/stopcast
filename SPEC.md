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
  is one tap. It asks for **precise location** (`ACCESS_FINE_LOCATION`): a coarse fix
  can be off by up to ~1 km, enough to read a stop half a mile away as the nearest, so
  precise is what makes "nearest" mean nearest. An *approximate*-only grant still works,
  degraded, rather than dead-ending. So the fix sent to TfL on demand is precise when the
  user grants precise **and an accurate fix is available** — it prefers GPS/fused, but falls
  back to an approximate (network/passive, or an approximate cached) fix when no accurate one
  can be obtained, so even under a precise grant the fix sent is occasionally approximate;
  under an approximate-only grant it is always approximate. Precise location is the Play Data
  Safety type the action **may collect** and so declares, not a claim that every fix is
  precise; either way it is never a background send. This is an in-app,
  on-demand action, never a background one. The list is a
  **useful, scannable spread, not a raw nearest-N**:
  - a **line appears once**, not once per stop it passes — a raw nearest-N repeats the same
    bus route several times, one per adjacent stop, which reads as noise;
  - a **denser mode doesn't crowd out another** — the nearest station of a mode surfaces even
    when several stops of another mode are closer, as long as it's within reach (so the
    nearest Tube shows even where bus stops dominate the immediate area);
  - it is **bounded to within reach** — on the order of a mile — so a far stop never appears
    just because nothing nearer shares its line. That reach is the TfL lookup's own radius; when
    the immediate ~0.2 mi around the user is empty the selection still keeps the single nearest
    stop within that reach, and a London locate effectively always has a stop within a mile — if
    somehow none does, an honest "couldn't find stops" beats reaching arbitrarily far. The
    current implementation imposes **no count cap**; whether a bound is ever needed to stay
    scannable at a dense interchange is undecided and tracked in `TODO.md`, not a cap imposed here;
  - it is **by line, both directions shown** for now — paired stops across a road serve a line
    in opposite directions, so neither direction is dropped; narrowing by direction or
    destination is a later refinement tied to *favorite destinations*;
  - a **closer closed stop is surfaced honestly** — its status is shown rather than silently
    routing the user to a farther open stop with no explanation.

  The exact selection policy (radii, per-mode counts, whether an overall count cap is needed,
  how "mode-stop" maps onto the TfL model) is still being shaped on-device and lives in
  `TODO.md`, but the constraints above are the settled product intent the implementation must
  satisfy.

  To keep the lookup fast and honest: a recent cached position is used at once; if a fresh fix
  is slow or absent, a *somewhat-stale* cached one substitutes for it rather than making the
  user wait or fail — but only within a bounded age, past which trackmo reports "couldn't get
  your location" rather than showing a previous location's stops as current (a user who has
  traveled would be misled). A failure to get a fix is always logged, so a misfire is
  diagnosable.

  The **current implementation** is distance-shaped rather than a fixed count — an inner ring
  of stops close by, plus the nearest stop of each *mode* a little farther out that the inner
  ring doesn't already cover, so a denser mode (London's bus stops) can't crowd out a sparser
  one (the nearest Tube or rail station still appears), with no overall count cap. **The radii
  are provisional and not yet validated on a device** — the illustrative values and the open
  questions (whether a dense interchange needs a scannability bound, how the list is ordered)
  live in `TODO.md`; what is durable is the constraints above, not the specific numbers. (How
  the *services* a line repeats across adjacent stops collapse to one row is *Departures*;
  this is only which stops are looked up.)
- **Search** — by stop name or by line, for pinning a stop the user isn't standing at
  (home, work, the school run).

### Departures

For each watched stop, trackmo shows the next few departures: **line**, **destination**
(where the service is headed), and a **countdown**. Countdowns render as minutes — "Due"
when imminent, "3 min", "12 min" — sorted soonest-first.

**The unit of display is a flat row per (service, stop, direction)** — a *service*
being a line (or bus route) at a stop — presented as a **compact card**: the line pill
and its destination as the headline, and the service's **next few countdowns merged onto
one line** ("Due · 3 · 6 min", the "min" unit written once). The **stop name is not shown
on the card for now**: it read as clutter in the compact layout, and on the lock-screen
widget — the surface this model is aiming at — the stop is implied by the context the user
set up, not something a glance needs restated per card. It returns with **multi-stop
watching** (Phase 2), where several stops share the list and the card must say which one
it is. A two-way service
at a stop is two cards, one per direction; a one-directional case (a terminus platform, a
one-way-street stop, a single branch) is one. Nothing is hidden behind a gesture, which
is what a glance surface needs (**D8**). TfL's `direction` is the primary key and the
domain retains it — it can't be *reconstructed* from destination or platform in general
(a branch shares a direction; a terminus doesn't imply one). TfL omits `direction` on
some services, though, so when it is absent the grouping falls back to the platform, then
the destination, as a best-effort discriminator (the resolved *direction key*) rather
than merging opposite directions; a prediction with none of the three is genuinely
indistinguishable and shares one "unknown" row. When a direction *does* branch
(same line, same direction, different destinations), the headline names the soonest
departure's destination and merges only that destination's times; each **divergent
destination keeps its own line and its own merged countdown**, so a countdown is never
shown under the wrong one.

**Platform is not shown** and the **direction is not labeled in words** ("inbound" /
"outbound" is TfL jargon): the destination *is* the direction signal a rider reads, so
the card carries the destination and drops both — the countdown, the one thing that must
always stay legible, keeps the room. The platform stays in the domain model for a later
surface (a detail view) but earns no space on the glance card. The destination elides to a
single line, so a long one ("Harrow & Wealdstone") truncates rather than wrapping the card
taller or pushing the countdown off the edge. The destination's **station-type suffix is
trimmed** the way stop names are — TfL's "Brixton Underground Station" shows as "Brixton"
(*Concise copy*); the bare " Station" is dropped too, so a terminus like "Battersea Power
Station" reads "Battersea Power" rather than running longer than every other label. **The
one exception is a destination-less
service**: when TfL gives neither a destination nor a "towards", the direction word — or,
failing that, the platform — is shown *in the destination's place* as the only cue that
keeps two directions of the same line distinct (never mislabel a countdown). That fallback
is the reason the platform and direction stay in the model; it is a safeguard, not a
reversal of dropping them when a real destination exists.

The row set is not purely prediction-derived: a watched stop or line with a **known
disruption** but **zero predictions** still contributes a row — a status row (for the
stop, or for that service at the stop) carrying the disruption and no countdown,
direction-independent since no prediction supplies a direction. So a suspended line or a
closed stop is surfaced, not silently dropped for want of a departure to build a row from
(see *Disruptions*) — the quietly-wrong failure the whole model exists to avoid.

The list shows the **watched stops'** rows (D1) — trackmo renders the stops the user
chose ahead of time, not "nearest to me" — ordered **location-free** so the view works
with location denied: soonest-first, with **starred** rows pinned to the top. Starring is
ranking only, separate from which stops are watched (add/remove membership). A star keys on
the row's `(stop, service, resolved direction key)` identity, so it restores to exactly one
row and survives restart. **Warning rows still lead**, above even a starred service — a stop
closure or a no-prediction line-status row is something the user must see, and pinning a
starred service above it would push a warning down the list (principle 2). Distance ranking
belongs to *finding* stops (near-me discovery, *Finding stops*), not to ordering the watched
list.

**The final display model, and how a direction is labeled, are still open.** The flat
list ships first because it is the simplest thing that is fully glanceable. A more
compact **(service, stop) card that swipes between directions** is the leading candidate
to iterate toward once the flat list has been used on a device — it collapses a two-way
service to one card but hides the other direction behind a gesture the widget host owns,
so it is a later call, not a prerequisite (**D8**). The direction label is the resolved
destination the domain carries (`destinationName`, else `towards`); a branching direction
already keeps **one line per destination** (each with its own countdown). One refinement
is recorded to explore (`TODO.md` Phase 2): labeling a direction by the **next branch or
interchange point** downstream rather than the terminus, feeding letting users set
**favorite destinations** to filter or rank by.

TfL's endpoint is named "Arrivals"; for a bus stop these are departures *from* that
stop, which is what a rider wants. Trackmo calls them departures throughout the UI.

Each service wears its line's identity: a pill filled with the line's official TfL color
carries the line's **three-letter code** (its first three letters, uppercased — VIC, BAK,
ELI; a bus keeps its route number), so the pill stays narrow and the row keeps its width
for the countdown, while the list still scans by line the way the network map does. **Every
pill shares one fixed width**, sized to the widest code shown (a four-character bus route),
so the codes form a tidy left column the eye runs straight down a card list, rather than a
ragged edge that steps in and out as each code's length changes; a shorter code centers in
the shared box, and the width holds the widest code complete rather than truncating it. The
full line name is the pill's accessible label, so a screen reader announces "Victoria",
not "VIC". Tube
lines take their color by line (Northern black, Central red, …); bus, DLR, the Elizabeth
line and trams take one color per mode; the six named Overground lines take their color by
line too, rendered as a hollow pill (below). The label's black-or-white color is
chosen by **APCA**, the perceptual contrast model headed into WCAG 3, rather than the
WCAG-2 luminance ratio: WCAG-2 is luminance-only and misreads white on saturated
mid-tones (Victoria, DLR, Bakerloo), rating black higher where white is plainly more
readable, so a pill can sit below the WCAG-2 AA number and still be the right, readable
choice. The color is decorative — the identity is always text (the code, plus the full name as the
accessible label), never color-only.
The six named Overground lines (TfL's 2024 renaming) each take their own line color, but as
a **hollow pill** — the card surface shows through, the line color is the border and the
label — rather than a solid fill. Two reasons: several of the Overground colors sit close to
a tube line's (Windrush red ≈ Central, Mildmay ≈ a tube blue), so a solid pill would read as
that tube line; and TfL itself draws the Overground as hollow/parallel lines — so the hollow
shape says "Overground, not tube" even where the color collides. The accent is nudged to
stay legible on the surface (darker on the light card, brighter on the dark one). An
Overground service whose id isn't one of the six (legacy `london-overground`) falls back to
the single mode orange; a mode still without a defined color (national rail) falls back to a
neutral pill rather than an invented shade — a cosmetic gap, not a correctness failure.

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

- The **app** refreshes on open, on return to the foreground, on pull-to-refresh, and
  **auto-refreshes once a minute while the screen is on** (paused when backgrounded).
  The primary targets are home users and an always-on **kiosk** display (see *Non-goals*
  / D6), where a screen left open all day must keep its predictions live without a manual
  pull. A failed auto-refresh keeps the last-good departures on screen with a "couldn't
  refresh" warning rather than blanking — and once the data is truly stale (past the
  threshold) the per-row countdowns are withheld, so nothing wrong is shown as live.
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
  Search` for finding stops by name, plus `/Line/Search/{query}` then `/Line/{id}/
  StopPoints` for finding a stop by line (Phase 2); `/StopPoint/{id}/Arrivals` for
  departures; `/StopPoint/{id}/Disruption`, `/Line/{ids}/Status` and `/Line/{ids}/
  Disruption` for disruptions.
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
to TfL — **precise** where the user granted precise and an accurate fix is available,
approximate under an approximate-only grant or when no accurate fix can be obtained (see
*Finding stops*) — and a departures lookup necessarily sends the watched stop
IDs. Stop **search** (Phase 2) likewise sends the typed stop-name or line query to TfL's
search endpoints. That is inherent to each feature and disclosed; precise location is the
Play Data Safety type the nearby action may collect (and so declares), not a claim that
every fix sent is precise.

All of trackmo's persisted config — watched stops, per-stop filters, row stars, any saved
favorite destinations, the user's `app_key` — and the last-good snapshot travel through
**Android's own backup and device-to-device transfer** — trackmo allows
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
- **D6 — The app refreshes on open, on foreground return, on pull-to-refresh, and
  auto-refreshes once a minute while the screen is on** (paused when backgrounded). The
  intended targets — home users and an always-on kiosk display — leave the screen open,
  so a one-minute cadence keeps TfL predictions (which update roughly every ~30 s) fresh
  without a manual pull, while staying a tiny fraction of the keyless per-IP rate budget.
  A failed tick keeps the last-good departures with a warning (D4), never a blank screen.
  The interval is one tuned constant in code, not a spec guarantee.
- **D7 — No baked-in TfL key; works keyless, optional user key for the higher limit.**
  A shared key would pool all users into one bucket and ship a credential; a per-user
  key avoids both. See *Data source*.
- **D8 — Ship a flat list of compact cards (one per service, stop, direction) with
  star-to-pin; final display model left open.** A station serves many lines and most run
  two ways, so the display has to present direction somehow. The flat list gives each
  direction its own card: nothing hidden, fully glanceable, and the simplest thing to
  build and to render on a widget. Each card is a **compact, near-uniform-height block** —
  line pill + destination headline, the next few countdowns merged onto one line — so the
  list scans evenly; only genuinely extra information (a disruption chip, a branch's second
  destination) adds height. The destination **elides** to one line so a long name never
  wraps or crowds out the countdown. **Platform, the "inbound/outbound" direction word, and
  (for now) the stop name are dropped from the card** — the destination is the direction
  signal a rider reads; the stop is implied by the widget's chosen context and returns with
  multi-stop watching (Phase 2); platform stays in the model for a later detail surface. Its cost is length — a busy stop is many cards — which starring (ranking,
  distinct from watched-stop membership) and, later, smarter selection are meant to manage.
  The list orders the watched stops' cards location-free (soonest-first, starred pinned),
  so it works with location denied; distance ranking is for *finding* stops, not ordering
  this list (D1). A more compact **(service, stop) card that swipes between directions** is
  the leading candidate to iterate toward, but it hides the other direction behind a
  gesture the widget host owns, so it is deferred until the flat list has been used on a
  device — not a prerequisite. TfL's `direction` is the primary key and is retained (it
  can't be reconstructed from destination/platform in general); when TfL omits it, grouping
  falls back to platform then destination as a best-effort discriminator, and an all-blank
  prediction shares one "unknown" row. A branching direction merges only the headline
  destination's times; each divergent destination keeps its own line and countdown, so
  none is mislabeled. One refinement remains recorded to explore (`TODO.md` Phase 2):
  labeling a direction by the next branch/interchange point downstream rather than the
  terminus, feeding user-set favorite destinations. Supersedes the earlier open question;
  the flat-list-vs-swipe-card choice is the remaining open call, to settle from real use.
