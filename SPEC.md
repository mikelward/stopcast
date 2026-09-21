# StopCast

StopCast shows live London transport departures for the stops you care about, at a
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
fleet. The lock-screen *placement* needs Android 16 QPR; stopcast's widget is a
standard widget that becomes lock-screen-eligible where the OS allows it, so there is
no separate lock-screen code path.

Coverage is London / TfL only. StopCast is not affiliated with Transport for London,
and uses the free, public TfL Unified API.

## Product behavior

StopCast watches a small set of **stops** and, for each, shows the next few departures
and any disruption that would change whether you'd trust them. A "stop" is a TfL
`StopPoint`: a bus stop, an Underground/Overground/Elizabeth-line/DLR station, a tram
stop, or a pier.

### Watched stops

The user builds a short list of **watched stops** — the stops stopcast shows. A watched
stop can be narrowed to specific **lines** and/or a **direction** (inbound/outbound,
or a named platform), so a surface shows only the departures the user actually takes —
"Victoria line southbound at Warren Street", not every service through the station.
The default is all lines, both directions.

Watched stops are the source of truth for every surface, chosen ahead of time — see
decision **D1** for why the widget renders watched stops rather than "nearest to me".

### Finding stops

The app finds stops two ways:

- **Near me now** — with location permission, stopcast lists the stops nearest the
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
  on-demand action, never a background one.

  The fix is taken when the near-me view first resolves **and again on every refresh**:
  the refresh control and pull-to-refresh re-resolve the nearby set as well as re-fetching
  departures, so a user walking from stop to stop sees the set follow them, one refresh at a
  time (the common "walk past a station and check" case). It stays user-initiated — no
  background or automatic polling — so the location send stays bounded by the user's own
  refreshes rather than a timer. The re-locate **forces a fresh fix** (it does not take the
  recent-cached fast path a first open may use): a rider who has walked since the last fix
  must not be re-resolved against the old position, so a cached fix is only a bounded fallback
  here — a fresh fix is ~1–2 s in the common case, comfortably under the refresh spinner.
  Re-resolving skips the "locating…" spinner on the common success path — the departures stay
  on screen during the fix rather than
  flashing back to the gate on every pull — but an unsuccessful re-resolve is **surfaced
  honestly, not swallowed** (principles 1–2): a failed fix, an unreachable lookup, or an
  out-of-range "no stops nearby" replaces the list rather than leaving a previous location's
  stops on screen as if current (cards omit the stop name, so a stale set is
  indistinguishable from the real one). There is therefore **no separate "locate" control**
  — refresh does both. (An automatic, distance-triggered version — update
  when the user moves ~100 m — is a later enhancement; the parameters and battery trade-offs
  are in `TODO.md`.) The list is a
  **useful, scannable spread, not a raw nearest-N**:
  - a **line appears once**, not once per stop it passes — a raw nearest-N repeats the same
    bus route several times, one per adjacent stop, which reads as noise;
  - a **denser mode doesn't crowd out another** — the nearest station of a mode surfaces even
    when several stops of another mode are closer, as long as it's within reach (so the
    nearest Tube shows even where bus stops dominate the immediate area);
  - it is **bounded to within reach** — on the order of a mile — so a far stop never appears
    just because nothing nearer shares its line. That reach is the TfL lookup's own radius; a
    London locate effectively always has a stop within a mile — if somehow none does, an honest
    "couldn't find stops" beats reaching arbitrarily far. The list shows the **nearest two
    clusters of each mode**, which keeps a dense interchange scannable and caps how many
    clusters are fetched; the clusters beyond that cap are reached through a per-mode **"More"**
    control at the foot of the list, each tap paging that mode's next clusters in (see below);
  - it is **by line, both directions shown** for now — paired stops across a road serve a line
    in opposite directions, so neither direction is dropped; narrowing by direction or
    destination is a later refinement tied to *favorite destinations*;
  - a **closer closed stop is surfaced honestly** — its status is shown rather than silently
    routing the user to a farther open stop with no explanation.

  The selection is **two-tier** (maintainer, 2026-09-21): stops group into **clusters** (a
  station's platforms, a bus junction's poles — keyed on TfL's `stationNaptan`, D8), and the
  **nearest two clusters of each mode** are *eager* — fetched and shown at once. Per-mode
  selection guarantees the nearest station of a sparse mode (a Tube up to the ~1 mile reach) is
  always eager, without a separate reserve rule. The cap is two rather than more because
  expanding is not free: TfL doesn't aggregate a bus junction, so each lettered pole is its own
  arrivals request, and a low eager cap keeps the list short and caps how many clusters are
  fetched — sharply fewer than "everything in reach" at a dense corner. It bounds the cluster
  *count*, not the request count: one large junction cluster is still an arrivals request per
  pole, so a hard per-cluster fetch budget is a `TODO.md` follow-up. The clusters beyond the cap
  are the *more* tier, reached through a per-mode **"More"** control at the foot of the list: a
  tap pages that mode's next clusters in (a bounded few per tap, so each tap's fetch stays small)
  and merges them beside the eager ones; a cluster serving two modes appears under each mode's
  "More". A **revealed expansion survives a relocation** — the near-me set re-resolves only on a
  user-initiated refresh, and the retained view is keyed on the *whole* nearby cluster set (both
  tiers, order-independent), so a small move that only reorders the clusters, or shifts one across
  the eager/more boundary while all stay in range, keeps what the user opened. When a relocation
  drops a revealed cluster (or one of its poles leaves range), that stop leaves the list at once
  and the reduced set is persisted, so it can't linger as current (D4). **The widget mirrors the
  app's current view** — eager plus whatever is revealed, not eager-only — so an in-app expansion
  grows the set the widget persists and its background refresh keeps polling, until the next
  relocation resets it (maintainer's lean, 2026-09-21; reversible to an eager-only widget snapshot
  — `TODO.md`).

  To keep the lookup fast and honest: a recent cached position is used at once; if a fresh fix
  is slow or absent, a *somewhat-stale* cached one substitutes for it rather than making the
  user wait or fail — but only within a bounded age, past which stopcast reports "couldn't get
  your location" rather than showing a previous location's stops as current (a user who has
  traveled would be misled). A failure to get a fix is always logged, so a misfire is
  diagnosable.

  Both tiers draw from one TfL `/StopPoint` lookup within the **~1 mile reach** (the lookup's
  own radius); the eager clusters' stops are fetched for arrivals at once, and a *more* cluster's
  stops are fetched when its "More" is tapped (above). **The reach and the two-per-mode cap are
  not yet validated on a device** — whether either wants tuning at a real interchange lives in
  `TODO.md`; what is durable is the two-tier shape and the constraints above. **The near-me list is ordered
  closest stop first**, with soonest-first breaking a same-stop tie (a stop's several services
  are equidistant); warnings still lead and starred rows are still pinned above it. Distance
  orders only this location-derived list — never the location-free watched list, which stays
  soonest-first (D1). Each near-me stop's header also **shows its distance** in parens after
  the name ("Oxford Circus (120 m)"), so a rider can judge which of two nearby stops to walk
  to rather than only reading the order; the watched list carries no distance and shows none
  (D1). The unit is metric (m/km) for now; making it follow the user's locale (m/yd, km/mi) and
  switching to a fractional large unit for longer distances are in `TODO.md`. That order is a
  provisional starting point to judge on a device (it keeps
  what's nearest on top and cuts reshuffles, at the cost of the closest stop leading even when
  nothing leaves it soon); the reasoning and alternatives live in `TODO.md`. (How the
  *services* a line repeats across adjacent stops collapse to one row is *Departures*; this is
  only which stops are looked up.)
- **Search** — by stop name or by line, for pinning a stop the user isn't standing at
  (home, work, the school run).

### Departures

For each watched stop, stopcast shows the next few departures: **line**, **destination**
(where the service is headed), and a **countdown**. Countdowns render as minutes — "0 min"
when imminent, "3 min", "12 min" — sorted soonest-first.

**The unit of display is a flat row per (service, stop, direction)** — a *service*
being a line (or bus route) at a stop — presented as a **compact card**: the line pill
and its destination as the headline, and the service's **next few countdowns merged onto
one line** ("0 · 3 · 6 min", the "min" unit written once). The **stop name is not
repeated on every card**: it read as clutter restated per row, and on the lock-screen
widget the stop is implied by the context the user set up. Instead, once the list spans
more than one place, a **small header above each group of same-place cards** names it,
in spaced small caps (**one header per place**). A *place* is the set of stops that share a
**cluster** — a bus junction's two poles, a station's several platforms — grouped together so
they read as one boarding location, the way a Tube station (a single stop id aggregating its
platforms) already did; grouping by stop id instead split a junction's northbound and southbound
poles into two identical headers (settled 2026-09-21). The cluster key is TfL's **`stationNaptan`**
where the nearby lookup gives one, else the cleaned display name. Keying on TfL's own cluster is
what keeps a station it spells several ways together (King's Cross St. Pancras has several forms,
so the name alone is an unreliable key) while holding genuinely distinct adjacent stations apart
where TfL gives them different clusters — the maintainer's worked example is keeping King's Cross
St. Pancras separate from St Pancras International (2026-09-21). The first step ships the **bare
name** alone. A **mode-aware direction/terminus qualifier** for which way the stop is headed — the
stop letter `(S)` or compass bearing `(→S)` TfL prints for a bus stop, a rail platform's
`· Southbound`, or `→ Terminus` toward a single destination, shown only when the whole stop
shares one direction/terminus — is the maintainer's lean for the header (settled from the
mocks, 2026-09-21) but is **deferred to a follow-up** so it can be designed on its own, and
the letter/bearing forms in any case await capturing TfL's StopPoint indicator (`TODO.md`).
A finer per-direction header, and the qualifier's grain at a busy interchange, were mocked
and are part of that same follow-up. A two-way service
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

On a **branching line** (the Northern most visibly) two trains to the same terminus can
run via different central trunks, and TfL names the trunk in `towards` ("Battersea Power
Station via Charing Cross") — the cue a rider uses to pick their train. So when `towards`
carries a "via", the branch is shown **joined after the destination in plain list style**
("Battersea Power, Charing X"). The branch **participates in grouping**: within a direction, a
line is split not just per destination (D8) but per terminus-and-branch, so two trains to
one terminus via different trunks (Edgware via Bank and via Charing Cross) each get their
own line and their own merged countdown. Merging across branches would label the later
train's countdown with the first train's branch, defeating the disambiguation — the
countdown must never sit under the wrong branch any more than under the wrong
destination.

TfL spells the same trunk several ways in its feed — the Northern line's two central
trunks arrive as "Bank", "Bank Branch", and "CX", plus the full "Charing Cross" — so the
label is normalized to one short board form per trunk ("Bank", "Charing X"), the spelling
a rider reads the same on every row.

The branch is shown only where it names a **choice the rider makes here**. Two trains to
one terminus by different trunks are the *same service* only once the trunks have
physically joined — past the junction, on the single shared track — "from the perspective
of someone traveling away from Bank and Charing Cross the origin doesn't matter". There the
rows **merge into one line and the branch label is dropped** (one "High Barnet" from
Highgate, not a "High Barnet, Bank" and a "High Barnet, Charing X"). The branch stays,
each row labeled, wherever the trunks are still distinct — including **at the junction and
trunk stops themselves** (Camden Town, Euston, Kennington): a Bank train and a Charing Cross
train reach Camden by different approaches (via Euston vs via Mornington Crescent) and leave
from different platforms, so the rider still picks one there even though both go to High
Barnet the same way onward. It stays too where a trunk-only stop lies ahead — High Barnet →
Morden picks which central stations you pass, Euston → High Barnet picks whether the train
calls at Mornington Crescent. The test is an **approach-inclusive path comparison** over
TfL's Route/Sequence data: from the stop *one before* this one through to the terminus, equal
sets of stops on both trunks ⇒ merge and drop the label; different ⇒ keep both. Including the
approach stop is what keeps the branch at the junction (the trunks reach it by different
approaches) while still merging once past it (the approach is then shared).

Resolution requires an **exact branch match** — the arrival's branch must name a route
pattern that actually serves this leg. Anything the asset doesn't model that way keeps TfL's
raw label and merges nothing: an unknown line, a stop or terminus off every pattern, or a
branch no serving pattern carries. That last case is Battersea Power Station — TfL tags its
trains "via Charing Cross" but its route pattern carries no "via", so the branch matches no
serving pattern and the train keeps ", Charing X", the trunk it runs (redundant but not
wrong; the maintainer prefers keeping it over dropping it). That data is a **bundled static
asset** (regenerated from TfL, no runtime cost on any path); incomplete or stale data
degrades to "show what TfL said", never to a confident wrong merge.

The one exception to that raw fallback is a **single-branch stop** — one every serving pattern
reaches on the same trunk. There the branch names which trunk the train came up behind this
stop, never a choice, so the label is dropped even for a short-working the asset doesn't model
as a terminus. King's Cross is Bank-only, so a Bank-branch train there terminating at Golders
Green or Finchley Central shows no ", Bank".

The **branch outranks the terminus for space**, because on a branching line the trunk is
what tells two otherwise-identical trains apart — losing it defeats the row. So where the
pair won't fit, the branch is kept (its board short form, "Charing X") and the **terminus
yields**, in two steps: it first shortens common whole words to a compact form
(`East`→`E.`, `Street`→`St`, and the rest — `DestinationAbbreviations`), and only a name
still too long after that is **hard-clipped with a clean cut** — no ellipsis (maintainer
preference: a `…` on a narrow row crushed the name to a glyph and read as a glitch). So a
tight row reads "E. Finchley", or "Batter, Charing X" at the extreme. The full name shows
whenever it fits and stays the accessible label throughout. Giving branch and terminus
equal width was considered and declined: the branch is always short, so equal space would
only clip the terminus sooner. A fuller rider-readable branch form under pressure
("Charing X"→"via Charing Cross"), which would reopen that balance, remains a tracked
refinement (`TODO.md`).

The row set is not purely prediction-derived: a watched stop or line with a **known
disruption** but **zero predictions** still contributes a row — a status row (for the
stop, or for that service at the stop) carrying the disruption and no countdown,
direction-independent since no prediction supplies a direction. So a suspended line or a
closed stop is surfaced, not silently dropped for want of a departure to build a row from
(see *Disruptions*) — the quietly-wrong failure the whole model exists to avoid.

The list shows the **watched stops'** rows (D1) — stopcast renders the stops the user
chose ahead of time, not "nearest to me" — ordered **location-free** so the view works
with location denied: soonest-first, with **starred** rows pinned to the top. Starring is
ranking only, separate from which stops are watched (add/remove membership). A star keys on
the row's `(stop, service, resolved direction key)` identity, so it restores to exactly one
row and survives restart. Starring is toggled by a **long-press on the row**, and a starred row
is marked by a **gold border** (no in-row element, so it costs no width) plus its position at the
top; the earlier per-row star button was removed because it consumed width on every card, and a
discoverable, labeled star returns with the tap-to-open stop detail view (*deferred*, `TODO.md`). **Warning rows still lead**, above even a starred service — a stop
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
terminus the domain carries (`destinationName`, else `towards` before its " via "), plus
the via-branch alongside it where TfL gives one (see the branching-line paragraph above); a
branching direction keeps **one line per terminus and branch** (each with its own
countdown). One refinement is recorded to explore (`TODO.md` Phase 2): labeling a direction
by the **next branch or interchange point** downstream rather than the terminus, feeding
letting users set **favorite destinations** to filter or rank by.

TfL's endpoint is named "Arrivals"; for a bus stop these are departures *from* that
stop, which is what a rider wants. StopCast calls them departures throughout the UI.

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
quality bar*). So stopcast surfaces, for watched stops and their lines:

- **Line status** — minor/severe delays, part-suspended, suspended (TfL line status).
- **Stop closures and stop-level disruptions** — a closed entrance, a moved stop.
- **Cancellations** of specific predicted services, where TfL exposes them.

A disrupted line is always kept flagged — its countdowns are never shown as verified-clean
(principle 1). The chip's label is TfL's own wording where it names the disruption ("Part
Closure", "Suspended", "Severe Delays"), and a concise label recovered from the free-text
reason where the wording is only TfL's vague bus catch-all "Special Service" — which names
nothing on its own, the real state (usually a diversion) living only in the text. So a
diverted bus reads "Diversion"; a vague status whose text yields nothing better falls back
to "Service Alert" — never the meaningless "Special Service" — rather than being hidden.
Pulling the affected stretch
("Diversion Moorgate to Monument") and the compact chip are follow-ups (`TODO.md` Phase 3).
TfL's `isNow` flag is not used to hide a "future" alert: it reads `false` even for planned
closures currently in effect, so telling current from future needs the dates in the text,
and showing a not-yet-current diversion is the safe side (an extra chip beats a hidden
disruption).

A stop-closure card carries **no header of its own** and is **collapsed to a single line,
titled on tap**: TfL's stop notices are prose (a paragraph on a lift outage), and a glance
surface shouldn't be dominated by one, so collapsed the card is the notice's first line alone,
and tapping expands it to a **title — the interchange name, else the stop** — over the full
text. The notice text carries its own place (TfL writes the station into it), so the alert
floats free of the per-place headers the departures group under, rather than repeating a name
above it.

On the **near-me list** a **hub-wide notice** — a lift outage, an accessibility closure — that
TfL reports against every stop point in an interchange is shown **once**, on the nearest member
stop, not once per platform: King's Cross St. Pancras and St Pancras International otherwise each
carry the identical "no step-free access" card. Those members carry distinct stop ids but one
shared **hub identity** (TfL's `hubNaptanCode` — both are `HUBKGX`), so the notice is folded by
that hub, kept once on the nearest member, and the expanded card titles itself by the
interchange (its name resolved from TfL, falling back to the stop's own name if the lookup
fails). Folding by hub identity — not by the notice text alone — is what keeps two genuinely
distinct places apart: TfL's text does not always name its own stop (a place-less "Station
closed"), and two unrelated closures with that identical text belong to different (or blank)
hubs, so each keeps its own card and no warning is dropped (principle 1). Two different notices
carried by different members of one hub likewise key apart and keep a card each. The fold keys on
a member's **joined** notice text (a stop's several disruptions are joined into its one
stop-status row), so where members of a hub carry *different sets* of notices a shared one is not
folded per individual notice — a rare residual (mostly one notice per stop) whose per-description
fix is a documented follow-up (`TODO.md`). This dedupes across the interchange without merging its
departures, which stay grouped per station (D8).

On a glance surface a disruption is a one-line summary plus a count ("Victoria line:
severe delays"); in the app it's the full text. A disrupted line/stop is marked even
when its predictions still look normal, because the prediction is the thing not to be
trusted — **and even when it has no predictions at all.** A suspended line often returns
zero arrivals, so stopcast retains the watched stop→line mapping independently of the
predictions and shows a line's status from that mapping; otherwise the surface would say
"no departures" for a suspended line and leave the user waiting for a service that isn't
coming — the quietly-wrong failure in its purest form.

Disruption and arrivals are separate requests, so a refresh can get one and not the
other. When the disruption lookup fails but arrivals succeed, stopcast does **not** present
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
is never held at "0 min" or shown as negative time for a service that has already gone —
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

StopCast never blanks or lies when it can't get fresh data. If TfL is unreachable, the
rate limit is hit, or location is denied, the surface says which ("offline", "can't
reach TfL", "location off") and shows the last good data stamped with its age, rather
than an empty box or unlabeled stale numbers.

### About and open-source licenses

An overflow menu in the departures top bar opens an About dialog naming the app and its
installed version. Its one action is the open-source licenses screen — the transitive
dependency graph, and for each component its version, authors, and license identity —
which stopcast ships to meet those licenses' attribution terms (Apache-2.0 §4 among them).
That attribution is exported at build time and bundled, so the list itself renders with no
network. The full license *text* is not bundled (following the sibling repos' export, which
omits it): each license links out to its canonical text, one tap to the browser.

### Settings

An overflow-menu entry opens a Settings screen, hosted at the activity top level like the
licenses screen (an overlay whose own Back closes it) rather than through a navigation graph
— stopcast still has no nav library. Its first setting is the opt-in "refresh widget every
minute" toggle (D5). The screen composable is UI-only: it reflects the setting and reports a
change, while persistence (a typed DataStore, mirroring the starred-rows store) and the
refresh scheduler (WorkManager) are wired by the activity, so the screen stays
JVM/Robolectric-renderable without touching Android services.

About — and so the license attribution — is reachable in **every** state, including the
location gate when permission is denied and departures never resolve: it is hosted above the
gate, not inside the departures view, so a user who never grants location can still open it.
Settings shares that top-level hosting, but is reached only from the departures overflow menu
for now (the gate's own menu offers About alone). Opening either takes the departures view
(and its background refresh) out of the picture, so nothing polls TfL behind the static
screen.

### Display size

The user can make StopCast's text bigger or smaller than everything else on the phone — a
glance surface is read at arm's length and from a pocket. The size is a **factor on top of the
system's own font scale**, not a replacement for it, so an accessibility setting made in
Android is still respected and StopCast only says how much larger or smaller it should be than
the rest of the device. It multiplies only text: paddings, icons, and touch targets keep the
4dp-grid layout, so larger text grows what is read without breaking what is tapped. The
offered range is 80%–160% of the system size — wide enough to help a low-vision reader,
bounded so a departure card's one-line countdown still lays out beside its line pill at the
top of the range (D8). The default is the system's own size (100%): StopCast follows the
platform setting until the user chooses otherwise, so the dense default layout stays as-is and
scaling is opt-in.

Two controls change the one stored size, kept in sync because they write the same value: a
**slider** on Settings and a **two-finger pinch anywhere in the app** (a pinch resizes the
*app*, not the page it happened on). A switch on Settings gates the pinch, for a user who
would rather not resize by accident. Both move the size *live* as they are used and persist
once, when the gesture or drag ends; a size arriving from storage while the fingers are down
is held rather than snapping the text out from under them. The size is warmed into memory at
startup so the first frame is already the user's size — corrected a frame later on a cold
start rather than held behind a blocking disk read (principles 3–5). The stored value is one
number and one boolean about how the app draws itself — nothing about the user, the place, or
the time — and travels with the rest of the config through Android's backup like any other
setting.

### Update indicator

When Google Play reports a newer version, the departures overflow (⋮) icon carries a small
red dot, and the menu gains an "Update available" item that opens the Play listing — Play
does the download and install. It is a lightweight nudge, not a banner: a dot costs no row
or top-bar width, and there is no in-app update flow to shoehorn a download/restart UI into.
Availability is Play's own answer, checked in the background on each foreground (never on a
render path). The check is release-only: a debug build's `.debug` applicationId isn't a Play
app, so it would only ever fail. An inconclusive check (Play absent or erroring) hides the
dot rather than guessing — the worst case is a missed nudge, and Play still updates the app
on its own schedule regardless.

This is stopcast's one off-device call that is not a TfL request. The Play In-App Update
library (`com.google.android.play:app-update`) is **free** and its check is off every render
path (a background Play `Task`). It sends **no user data** — no location, no watched stops,
no API key: it is a Play Services query about the app's *own* update availability (the
package and installed version Google Play already knows as the app's distributor), so it
adds **no new Play Data Safety surface**. If Play is unavailable the feature silently no-ops
(dot hidden). A failed check logs the exception's class name only (PII-free) — see
`docs/PRIVACY.md`.

## Architecture

- **Kotlin + Jetpack Compose**, a single `:app` module (mirroring simmo and Type
  Launcher), with all product logic in a pure-Kotlin **domain** layer (`app.stopcast.
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

StopCast's one **data** dependency is the **TfL Unified API** — free and public. (A
release build also makes one non-data Play Services call to check for app updates — see
*Update indicator* above; it is free, carries no user data, and adds no Data Safety
surface.)

- Endpoints: `/StopPoint` (nearby by lat/lon + stop types + radius) and `/StopPoint/
  Search` for finding stops by name, plus `/Line/Search/{query}` then `/Line/{id}/
  StopPoints` for finding a stop by line (Phase 2); `/StopPoint/{id}/Arrivals` for
  departures; `/StopPoint/{id}/Disruption`, `/Line/{ids}/Status` and `/Line/{ids}/
  Disruption` for disruptions.
- **Cost: £0.** Anonymous access is limited to ~50 requests/min; a free, user-supplied
  `app_key` raises it to ~500/min.
- **D7 — stopcast ships no baked-in key.** It works keyless out of the box, and a user
  may paste their own free `app_key` in settings for the higher limit. A shared, baked-in
  key would pool every user's traffic into one 500/min bucket and put a credential in
  the APK; a per-user key does neither.
- **Reliability:** one dependency, so if TfL is down or throttling, stopcast shows
  stamped last-good data and an offline/rate-limited notice (never a blank or an
  unlabeled stale number). Added latency lives off every render path (snapshot-render,
  above).

## One widget, many surfaces

There is one widget. On Android 16 QPR and later, where the OS re-added widgets to the
phone lock screen, it is eligible to sit there; everywhere else it is a home-screen
widget. Both use the standard AppWidget/Glance API — a lock-screen widget is just a
widget the host is allowed to place on the keyguard — so there is no lock-screen-specific
code path to maintain. StopCast does **not** opt out of lock-screen placement (the
`not_keyguard` category). The app and its home-screen widget run on the fleet floor
(Android 14 / API 34); the lock-screen *placement* simply appears on devices new enough
to offer it.

The widget renders the **persisted last-good snapshot** the app writes — never the
network. It reads the snapshot once when the host asks it to update and renders from it,
so it can't stall on a fetch, and it stamps the data's age and marks it stale rather than
passing old times off as live (D4). Its rows **mirror the in-app list**: the same rows
grouped the same way — one line per (destination, branch), so a branching service's
divergent trains each keep their own countdown — and the user's **starred** services
pinned to the top (D8), sharing the domain's grouping and pinning so the two surfaces can't
drift. It shows the via-branch in the same normalized short form as the app ("Charing X"):
the label is one short form per trunk on every surface, so neither has to measure a fuller
name. (Reordering the nearby set closest-first is
not yet mirrored — it needs per-stop distances the snapshot doesn't carry and is moot once
Phase 2's watched stops replace the interim nearby source.) The app pushes an update whenever it fetches, so the
widget follows the app's last refresh rather than waking on the OS's periodic schedule
(battery). Because the widget's host never re-renders it on its own (no periodic update),
the widget also schedules **one render-only redraw at its staleness boundary**, so a widget
left untouched after the app closes flips itself to the stale `?` treatment instead of
holding live-looking countdowns forever (D4) — a single bounded wake per snapshot, not a
polling cadence, and not a data refresh (fetching new data while the app isn't driving the
widget stays deferred, D5). **Interim data source**: until Phase 2's user-chosen watched stops exist, the
widget shows the last *nearby* set the app fetched — "the stops near where you last
opened the app". Phase 2 replaces that with the watched stops; a live-refresh cadence for
the widget when the app isn't driving it is deferred (D5).

## Privacy

StopCast handles location and the set of stops the user watches — which together reveal
where they live, work, and travel. StopCast itself sends none of it anywhere except the
TfL requests that *are* the product: a nearby-stops lookup necessarily sends coordinates
to TfL — **precise** where the user granted precise and an accurate fix is available,
approximate under an approximate-only grant or when no accurate fix can be obtained (see
*Finding stops*) — and a departures lookup necessarily sends the watched stop
IDs. Stop **search** (Phase 2) likewise sends the typed stop-name or line query to TfL's
search endpoints. That is inherent to each feature and disclosed; precise location is the
Play Data Safety type the nearby action may collect (and so declares), not a claim that
every fix sent is precise.

All of stopcast's persisted config — watched stops, per-stop filters, row stars, any saved
favorite destinations, the user's `app_key` — and the last-good snapshot travel through
**Android's own backup and device-to-device transfer** — stopcast allows
both, deliberately, so a phone swap keeps the user's setup rather than losing it
(maintainer, 2026-09-18; the fleet's "never lose the user's work" over a literal
never-leaves-the-device wording). This is the platform's user-controlled channel tied to
the user's own Google account, not an off-device channel stopcast adds: cost £0, and no
Play Data Safety change (Android Auto Backup is a platform feature, not data stopcast
collects or transmits). The guarantee is therefore precise, not absolute — the only **user data**
*stopcast* sends off the device on its own goes in its TfL requests (its one other network
call, the release-only Play update check, carries none — see *Update indicator*); the user's
own backup/transfer carries their config under their control; and a **consent-gated bug
report** (see below and `docs/PRIVACY.md`) carries the exact location and per-stop distances
the user explicitly agrees to share on a screen they can decline.

No **user data** else leaves the device unbidden — no analytics over the user's stops or
movements, and no coordinate, stop list, or API key in logs, commits, PRs, or fixtures; the
consent-gated bug report is the one user-authorized exception, and it discloses exactly what
it carries before anything leaves. The one off-device call that is not a TfL request is the
release-only Play update-availability check (*Update indicator*): a Play Services query about
the app's own version that carries no user data and adds no Data Safety surface. The on-device
debug log carries coarse diagnostics only: a stop ID, a line id, an HTTP status, or a
failed Play update check's exception class — never a raw coordinate or the user's API key.

## Engineering quality bar

In priority order; where a rule below conflicts with a principle, the principle wins.

1. **Never show a departure stopcast doesn't stand behind.** The worst outcome is the
   user missing a bus, or running for a cancelled one, because stopcast showed a number
   it shouldn't have trusted. Stale-but-unlabeled, or a normal-looking prediction for a
   suspended line, is worse than an honest "can't refresh" or "severe delays". Every
   surface is honest about age and disruption.
2. **Never fail silently.** If stopcast can't refresh — offline, rate-limited, location
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
- **Diagnostics are a persisted, on-device debug log**, off every render path: warnings are
  buffered and written to a rotating file in app-private storage that survives a crash or a
  silent process kill, so a misbehaving fix or refresh can be diagnosed after the fact (serves
  *never fail silently*). It stays on the device — no off-device sink — and carries coarse
  diagnostics only (see *Data source, cost, and reliability*). The implementation is the shared
  `mikelward/androidlog` buffer, resolved as a published dependency.
- **A bug report leaves the device only under explicit consent.** The overflow's *Send bug
  report* composes the log plus the **exact location** and per-stop distances and hands it to the
  platform share sheet — user-initiated, £0, no service of stopcast's own. Because it carries the
  location the log itself never does, it is gated by a consent screen that names exactly what
  leaves, with a persisted "don't ask again"; nothing is assembled until the user passes it. It is
  the *honest* report, not a location-safe one — a routing bug is diagnosed from where you were,
  so it says so rather than stripping that context (a separate location-redacted export stays a
  distinct, planned tool). It rides the shared `mikelward/androidlog` `DebugReport`; a screenshot
  is a planned addition, pending that library gaining screenshot support. See `docs/PRIVACY.md`.

## Non-goals

- **Journey planning / routing** (the TfL Journey API). StopCast answers "what's next
  from here", not "how do I get there".
- **Non-TfL operators** outside the Unified API (National Rail services TfL doesn't
  carry, coach, etc.).
- **Ticketing**, Oyster/contactless balances, and service maps.
- **Writing to TfL.** StopCast is read-only.
- **Continuous background location / geofencing.** Location is used on demand in the
  app to find nearby stops, never tracked in the background.

## Decision log

- **D1 — The widget renders watched stops; the app offers both watched and nearest.**
  The lock screen is a glance surface that must always have something to show without
  waiting on a location fix — and background location on the keyguard is restricted,
  often ungranted, and battery-costly. So what a surface shows is chosen ahead of time.
  The app still uses on-demand location to *find and suggest* nearby stops to pin, and
  to show a "near me now" list. Location stays off the **background and widget** refresh
  paths — those re-fetch a fixed set of stops with no fix (the widget's persisted watched
  set; the near-me view's already-resolved set). The one path that does take a fix is a
  **manual near-me refresh**: a user refresh (button or pull-to-refresh) on the near-me
  departures re-resolves the nearby set as well as re-fetching, so walking to the next stop
  and refreshing follows the user (see *Finding stops*). That is still on-demand and
  foreground — a user gesture, never a timer or a background wake.
- **D2 — A watched stop can be filtered to lines and/or a direction.** A station serves
  many lines and platforms; the rider takes one or two. Default is all.
- **D3 — Disruptions are surfaced alongside departures, and mark the line/stop even
  when predictions look normal.** A time for a cancelled or suspended service is the
  "quietly wrong" failure; the disruption is what makes the number trustworthy or not.
- **D4 — No surface presents stale data as live.** Data is stamped with its fetch age;
  countdowns recompute from the fetch time client-side; an expired prediction drops off
  the list rather than sticking at "0 min"; and a single shared staleness threshold (one
  tuned constant, not a per-surface number) decides when numbers are withheld for "tap
  to refresh".
- **D5 — Widget refresh is opportunistic and bounded, not aggressive polling.** Tap,
  host update, and a bounded periodic schedule while plausibly visible; degrade to
  on-demand. The interval is a battery-tuning detail, not a spec guarantee. Keeping the
  widget *honest* is separate from refreshing its *data*: because the host never re-renders
  a static widget on its own, the widget schedules one render-only redraw at its staleness
  boundary (a single bounded wake per snapshot) so it flips to the stale treatment when the
  app is closed (D4) — fetching new data on that schedule was the deferred part.
  - **Opt-in live refresh (off by default).** A Settings toggle, "refresh widget every
    minute", drives a self-rescheduling one-shot WorkManager chain that re-fetches
    arrivals for exactly the widget's persisted stops (location-free, D1) about once a
    minute and saves the refreshed snapshot, which pokes the widget to re-render. It is
    off by default because it costs battery and data the passive widget doesn't. A failed
    cycle keeps the last-good and still reschedules, so a transient TfL error doesn't
    break the chain. This is a **deferrable** one-shot, not a foreground service: the OS
    runs it roughly once a minute while the device is active and defers it under Doze
    (screen off and unplugged), but it is **not screen-state-gated**. With the app closed
    there is no live component to hear screen on/off, so the chain can still run with the
    screen off while charging (Doze may not engage) — which is why the setting's copy
    describes it as a background refresh, not a screen-on-only guarantee (decided with the
    maintainer, 2026-09, on Codex's finding that the earlier "while the screen is on" copy
    over-promised). A true screen-on-only scope — and guaranteeing the exact minute with
    the screen off — would need a foreground service, its persistent notification, and the
    Play foreground-service-type policy that carries; that is the deferred follow-up
    (mechanism A, *Widget follow-ups* in `TODO.md`).
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
  wraps or crowds out the countdown. **Platform and the "inbound/outbound" direction word are dropped from the card** — the
  destination is the direction signal a rider reads; platform stays in the model for a
  later detail surface. **The stop name is not on the card but returns as a group header**:
  the list is **clustered by place (stops sharing a cluster — a junction's poles, a
  station's platforms), one header per place**, showing the **bare name** in
  this step. The cluster key is TfL's `stationNaptan` where the nearby lookup gives one,
  else the cleaned display name — keying on TfL's own cluster keeps a station it spells
  several ways together while holding distinct adjacent stations (King's Cross St. Pancras
  vs St Pancras International) apart. A mode-aware direction/terminus qualifier (stop letter `(S)` / bearing `(→S)`
  for a bus, `· Southbound` for a rail platform, else `→ Terminus`, only when the whole stop
  shares one) is the maintainer's lean but is **deferred to a follow-up**, along with the
  per-direction grain at a busy interchange (both mocked 2026-09-21; letter/bearing also
  pending capture of TfL's StopPoint indicator — `TODO.md`). Its cost is length — a busy stop is many cards — which starring (ranking,
  distinct from watched-stop membership) and, later, smarter selection are meant to manage.
  The list orders the watched stops' cards location-free (soonest-first, starred pinned),
  so it works with location denied; the grouping then clusters that order by place (a
  place's cards stay adjacent, led by its soonest) without changing which place leads.
  Distance ranking is for *finding* stops, not ordering this list (D1). A more compact
  **(service, stop) card that swipes between directions** is
  the leading candidate to iterate toward, but it hides the other direction behind a
  gesture the widget host owns, so it is deferred until the flat list has been used on a
  device — not a prerequisite. TfL's `direction` is the primary key and is retained (it
  can't be reconstructed from destination/platform in general); when TfL omits it, grouping
  falls back to platform then destination as a best-effort discriminator, and an all-blank
  prediction shares one "unknown" row. A branching direction merges only the headline
  destination's times; each divergent destination — and each via-branch of one terminus —
  keeps its own line and countdown, so none is mislabeled. One refinement remains recorded
  to explore (`TODO.md` Phase 2):
  labeling a direction by the next branch/interchange point downstream rather than the
  terminus, feeding user-set favorite destinations. Supersedes the earlier open question;
  the flat-list-vs-swipe-card choice is the remaining open call, to settle from real use.
