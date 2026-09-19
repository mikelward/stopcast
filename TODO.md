# TODO

Phased plan toward the product in `SPEC.md`. Each phase lands as its own PR (or a small
stack), fully unit-tested, with `./gradlew test` and `./gradlew lint` green. Check items
off as they land; add newly discovered work to the right phase.

The ordering follows the maintainer's call that the **in-app view is the first
deliverable** (SPEC intro): the app screen is testable without the lock-screen host and
exercises the whole spine the widget later renders from.

## Phase 0 — Project scaffolding

- [x] Gradle build: single `:app` module, Compose, DataStore, `mikelward/androidlog`
      as a resolved dependency (mirrors simmo's `settings.gradle.kts` /
      `libs.versions.toml`). kotlinx.serialization lands with the TfL models in Phase 1.
- [x] `minSdk 34`, `targetSdk 36` / `compileSdk 37` per the fleet, versionCode from the
      git commit count.
- [x] `.claude/hooks/session-start.sh` to provision the Android SDK on web sessions.
- [x] CI: the fleet `ci.yml` runs a `build` job (`./gradlew test` + `lint` + debug APK)
      behind the `lanes` classify/gate, on PRs and `main`.
- [x] Shared checks wired: `lanes` (`.github/lanes.conf`), `codex` (the
      `mikelward/codex-review` workflows), `zizmor` — added by the fleet CI scaffold (#3).
- [x] Green `./gradlew test` and `./gradlew lint` (the aggregate tasks AGENTS.md
      requires — CI runs these, not the debug-only variants).

### Phase 0 — remaining (follow-up PRs)

- [ ] Screenshot job — **record + upload landed** with `MainScreen` (the `build` job
      runs the screenshot tests for pass/fail; the `screenshot-tests` job re-runs them in
      record mode and uploads the PNGs). Still to do: **drift refresh** (record the
      canonical set back onto the PR branch, since local and CI rendering can differ) and
      the **before/after visual-diff PR comment** (the `mikelward/ci-commit-artifact`
      apparatus the siblings use). Until that lands, committed baselines are reviewed via
      the uploaded artifact, and CI does not yet gate on pixel drift.
- [ ] Deploy job (Play internal track, release notes from commit subjects) — Phase 5,
      needs the signing secrets.
- [ ] `AboutLibraries` licenses export + Licenses screen scaffolding.

## Phase 1 — In-app departures view (first deliverable)

- [x] TfL Arrivals client behind a domain interface (`TflClient`):
      `/StopPoint/{id}/Arrivals`, kotlinx.serialization DTOs mapped to `Departure`,
      Ktor + OkHttp, recorded-fixture (`MockEngine`) tests. (Nearby `/StopPoint` lookup
      lands with Phase 2's "near me now".)
- [x] Domain (pure Kotlin, JVM-tested): arrival→countdown formatting ("Due"/"3 min"),
      soonest-first ordering, expired-prediction drop (a countdown reaching zero leaves
      the list, never sticks at "Due"), a single shared staleness threshold, nearest-stop
      ranking. (`Departure`, `Countdown`, `Staleness`, `NearestStops` + JVM tests.)
- [x] Retain TfL's `direction` (inbound/outbound) on `TflArrivalDto` → `Departure` as the
      primary grouping key — it can't be reconstructed from destination/platform in general
      (a branch shares a direction) — then group departures into **(service, stop,
      direction) rows**: a pure domain grouping, soonest-first within each row, JVM-tested
      (D8). When TfL omits `direction`, the key falls back to platform then destination as
      a best-effort discriminator, so opposite directions stay apart whenever TfL gives any
      of those — a prediction with none of the three has nothing to key on and shares one
      "unknown" row.
- [x] `MainScreen`: a **flat list of compact cards, one per (service, stop, direction)**
      (D8) over a seed set this phase — line pill + destination, and the service's next few
      countdowns **merged onto one line** ("Due · 3 · 6 min"), with the "updated N ago"
      stamp and client-side countdown recompute. Ordered location-free (soonest-first).
      (The stop name is not on the card for now — see the row-merge item below.)
      **Star-to-pin lands in Phase 2 with its
      persistence** — not here — so a star always survives restart rather than resetting
      (a half-persisted control loses the user's ordering). The compact swipe-card model
      is a later candidate too (SPEC D8). Landed with `MainViewModel` (fetches the seed
      off the main thread, maps failures to a typed `TflException` → honest offline /
      rate-limited / can't-reach-TfL states), `DepartureRows.across` for the merged
      soonest-first list, and `MainScreenScreenshotTest` (loaded light/dark, empty,
      offline).
    - [x] **Row-merge card redesign** (this PR): the per-departure rows became one compact
      card per service — merged countdowns via `Countdown.mergedLabel`, destination as the
      elided headline (no "inbound/outbound" word). **Platform and (for now) the stop name
      are dropped from the card** — the stop read as clutter in the compact layout and is
      implied by the widget's chosen context; it returns with multi-stop watching (Phase 2).
      Platform stays in the domain model for a later detail surface. A branching direction
      merges only the headline destination's times and keeps each divergent destination on
      its own line, so no countdown sits under the wrong one (D8). Near-uniform card height;
      only a disruption chip or a branch adds a line.
- [x] **Minimal disruption marking** — the honesty floor the first view can't ship
      without (SPEC principle 1 / D3); the *full* disruption experience is Phase 3.
      Landed incrementally across PR #12 (line-status marking + partial-failure handling),
      PR #14 (zero-prediction status rows), and PR #15 (stop closures). Each bullet below
      records its PR.
  - `/Line/{ids}/Status` for the shown stops' lines: **[landed, PR #12]** mark a departure
    whose line is disrupted (a chip carrying TfL's status wording); a good-service line is
    left unmarked. **[landed, PR #14]** show a line's status even when it has zero
    predictions (a suspended line often returns none) as a direction-independent status row,
    from the stop's declared lines (seeded per stop on `StopRef`/`StopArrivals`, carried
    independently of the predictions; Phase 2's watched stops replace the seed). The status
    lookup now covers declared lines too, and `DepartureRows.across` synthesizes a
    status-only row (empty `upcoming`, sorted first) for a disrupted declared line with no
    prediction rows (SPEC *Departures* / *Disruptions*).
  - `/StopPoint/{id}/Disruption` for the watched stops: **[landed, PR #15]** a stop's own
    disruption surfaces as a stop-level status row (sorted above the line-status and timed
    rows) even when its lines' status is normal, so a closed stop isn't shown with
    valid-looking departures. Trackmo **marks** (keeps the departures, adds the row) rather
    than suppresses — TfL's closure data is coarse and often absent, so hiding departures on
    it would risk dropping valid ones; and it surfaces **any** stop disruption rather than
    classifying closures (that's Phase 3). A closed stop with zero predictions still surfaces
    the row (SPEC *Departures*), since the row is built from the stop's disruption, not a
    departure.
  - Handle a **partial refresh** (arrivals succeed, disruption lookup fails): **[landed,
    PR #12]** the line-status lookup failing flags the shown departures "status unknown"
    (a banner) rather than presenting them as verified-clean (SPEC *Disruptions*). Keeping
    the *aged last-good* disruption state instead rides with the persisted snapshot (a
    later Phase 1 item — there's no persisted last-good to fall back to yet).
- [ ] **`docs/PRIVACY.md` describing the debug log's contents** — moved up from Phase 5:
      Phase 1 introduces the logger and Phase 0 the deploy pipeline, so a build carrying
      the log can reach testers now, and AGENTS.md requires the disclosure to exist before
      the log ships.
- [x] **Persist the last-good snapshot; show a stamped placeholder at once and fill it
      in when the async read completes** (SPEC snapshot-render — never block the first
      frame on the DataStore read; the intro says the first deliverable exercises
      persistence). This is what gives the offline state something to show after process
      death — the two belong together, so snapshot storage lands here, not in Phase 2.
      **The in-memory per-stop snapshot model landed in PR #16 (below); what remained was
      persisting it to DataStore and the stamped-placeholder first frame.**
    - **[landed, this PR] `SnapshotStore` over DataStore + kotlinx.serialization.** A
      `DeparturesSnapshot` domain type (the honest last-good: the stops at their per-stop
      ages, no transient cycle flags), a `SnapshotStore` seam, and a DataStore-backed
      implementation serializing a `data`-layer `PersistedSnapshot` DTO as JSON (Instants as
      epoch millis; a `version` field discards a forward-incompatible format rather than
      mis-reading it; corrupt/empty bytes read as "no last-good"). `MainViewModel` restores
      it on init — the first frame is the Loading placeholder, the async read fills in the
      aged snapshot — and the restored snapshot becomes the **prior the first refresh merges
      into**, so a stop that then fails to refresh keeps its aged rows. Each successful
      refresh persists the new last-good; an empty/error result never clobbers a good saved
      one. The DataStore is a process singleton (one instance per file, so the widget can
      share it). The store opens no off-device channel of its own (SPEC *Privacy*): a private
      file that rides Android backup / device-to-device transfer like the rest of the app's
      data (SPEC §12), a platform path the user controls. **This is the store the Glance
      widget reads next.**
  - **[landed, PR #16] Per-stop last-good with honest per-stop ages.** MainScreen's
    snapshot was one whole-list `Loaded(stops, fetchedAt)` replaced wholesale each fetch,
    so a partial refresh dropped the failed stop's rows entirely and one screen-wide flag
    withheld every stop's countdowns when the snapshot aged. Each `StopArrivals` now
    carries its own `fetchedAt`; a refresh merges into the prior snapshot (update the
    stops that succeeded, keep the ones that failed at their older age, via the pure
    `Snapshot.mergeStop`); and staleness/withhold is per row from each stop's age. This
    was the design the repeated MainScreen review findings pointed at (deferred from
    PR #10, Codex P1 on `3c4befb`); it deletes the drop-on-partial-refresh class rather
    than patching it.
    - **[landed, PR #16] Also decouple a stop's disruption fetch from its arrivals**
      (deferred from PR #15, Codex P2 on `ba72fa1`): a stop's `/StopPoint/{id}/Disruption`
      is now fetched independently of its arrivals, so a stop whose arrivals fail still
      surfaces its available closure (a stop-status row) rather than dropping out — the
      same drop-on-partial-refresh class by a different path, now closed.
- [ ] Offline / rate-limited / error states rendered honestly (SPEC principles 1–2),
      backed by the persisted snapshot above.
- [ ] Unit tests for the domain; Robolectric + Roborazzi screenshot tests for the
      screen and its empty/offline/disrupted states, wired into the CI allow-list.

## Phase 2 — Watched stops and settings

- [ ] Add/remove **watched stops** (the source of truth for what's shown) — added from
      search or nearby discovery, removed explicitly; persist the set. Distinct from
      starring; removing a multi-line stop drops all its rows.
- [ ] **Restore the stop name to the departure card when the list spans more than one
      stop.** The compact-card redesign dropped it (too much clutter, and implicit on a
      single-stop widget), but the current seed is already multi-stop (Oxford Circus +
      King's Cross), so a card gives no boarding location and a countdown can't be told
      apart from the other station's (SPEC D1 / principle 1). Accepted as an MVP
      limitation on the demo seed (maintainer, ship-MVP call); once real watched stops
      land, show the stop (a subline, or only when >1 distinct stop is on screen) so
      cards stay unambiguous. Codex P1 on PR #17 (`discussion_r4049648510`).
- [ ] **Star** rows to reorder them to the top — ranking only, not membership; persisted
      via DataStore so a star survives restart (D8). Key the star by the full
      **`(stop, service, resolved direction key)`** identity — the same three parts that
      identify a row — with the **resolved direction key** (`DepartureRow.directionKey`:
      direction, else platform, else destination) as the third part in place of raw
      `direction`, so a star restores to exactly one row: stop and service disambiguate
      across rows, and the resolved key keeps blank-`direction` fallback siblings at one
      stop apart. This is where star-to-pin's control *and* its persistence
      land together. Like the rest of
      the persisted config, stars ride Android backup/transfer — covered by SPEC
      *Privacy*'s backup note, not an app-initiated send.
- [ ] "Near me now" discovery (on-demand location, nearby `/StopPoint` lookup ranked by
      `NearestStops`) with one-tap add-to-watched; stop search. Distance ranking lives
      here — for *finding* stops to watch — not in ordering the watched list, which stays
      location-free so the view works with location denied (D1).
- [ ] Per-stop line/direction filters (D2).
- [ ] **Filter or rank by a destination the user enters, and let them save favorite
      destinations** — the user names where they're going (or picks a saved favorite) and
      trackmo surfaces the rows that get them there, complementing starring. Scope it to
      **on-device matching** against each row's retained destination text, so the
      destination is never sent to any network service. (Matching richer strings — raw
      `towards` like "Pimlico, Grosvenor Road", or the branch/interchange label below —
      means retaining those fields when that work lands; today only the resolved
      destination is kept, so scope the first cut to that.) Saved favorites persist like
      the rest of the user's config and so ride the platform backup/transfer — the
      platform channel, not an app-initiated send, and already covered by SPEC *Privacy*'s
      backup note. An
      off-device "does this stop reach X" lookup (TfL Journey Planner) would transmit the
      destination to TfL and is a **separate product + privacy decision** (SPEC declares
      the Journey API a non-goal; it would change the Play Data Safety answers), not
      assumed by this item.
- [ ] Optional user `app_key` in settings (D7).
- [ ] Extend the persisted snapshot (from Phase 1) to cover the watched-stop set,
      filters, and key. **This is what re-enables persistence for the location view**: the
      interim nearby view (PR #21) uses no persisted snapshot, because one process-wide
      snapshot can't represent a set that changes as the user moves (restoring it would show
      a previous location's departures under the newly-resolved stops). Keying the snapshot
      by stop set brings back the instant first frame and gives the offline/failed state
      last-good departures to show instead of only the gate (Codex, PR #21).
- [ ] (Later) Smarter row selection beyond starring — Home/Work "show whichever you're
      *not* near", direction by time of day — heuristic- and location-dependent, and the
      widget is location-free at refresh (D1), so a wrong guess is its own quietly-wrong
      risk; scope carefully (D8).
- [ ] (Later, open call) Evaluate the compact **(service, stop) swipe-card** model
      against the flat list from real device use (D8) — collapses a two-way service to
      one card, but hides the off-screen direction behind a host-owned gesture and needs
      a widget answer for it (a per-service direction filter or "widget shows the
      stop-wide D2 filter"). Owns the swipe work if adopted; not committed MVP scope.
- [ ] (Later, open call) **Label a direction by the next branch/interchange point, not
      the terminus** (D8). London-only, so tractable: from TfL route sequences
      (`/Line/{id}/Route/Sequence/{direction}` — fetch the inbound and outbound variants;
      static-ish, free, cached ahead of time — off the decision path; sends only line ids,
      no user data, so no Play Data Safety change),
      walk downstream from the boarding stop to the first point where the
      route *branches* or meets another useful line/mode, and show that point's name
      ("towards Highgate") instead of TfL's terminus. Key constraint: only a genuine
      branch or interchange counts — a stop merely shared with an unrelated route is not a
      junction. Replaces the terminus label the MVP ships with.
- [ ] (Later, open call) **One row per destination** as an alternative grouping to
      (service, stop, direction) (D8) — every row then names a single unambiguous
      destination (and handles a blank `direction` via `towards`), at the cost of more
      rows for a branching line. Explore against the shipped keying from real use.
- [ ] (Later) **Two-tone Overground pill.** The 2024 named Overground lines (Lioness,
      Mildmay, Windrush, Weaver, Suffragette, Liberty) use a two-color banded scheme the
      single-fill pill can't render, so every Overground line currently shows one
      placeholder color by mode (SPEC). Needs a two-color pill and a line→colors map to
      color each named line correctly.
- [ ] (Later, open call) **Show every destination departing in the next ~30 min, else
      just the next** (D8) — a time-window rule for what a row/list shows: surface all
      distinct destinations with a departure inside the window, and fall back to only the
      single next departure when the window is empty, so a quiet stop still shows
      something. An alternative to a fixed "next few per row"; explore from real use.
- [ ] (Later, open call) **Line-name chip readability** — improve the line pill's
      contrast and legibility. Candidate: show the first three letters of the line name
      and give every chip the same fixed width, so the pills form a tidy column instead of
      ragged-width blobs. Explore on a device against the current full-name pill.
- [ ] (Later, open call) **Configurable font size** — the user likes the current dense
      layout; make the text size a setting, with a slightly larger default a candidate.
      (Raised while starting the location work.)
- [ ] (Later, open call) **Walk-time reachability filter** — hide departures the user
      couldn't physically reach in time. Rough model: ~6 km/h ≈ 100 m/min walking, so a
      stop 200 m away is ~2 min out; drop a departure leaving sooner than the walk time to
      its stop. Needs the per-stop distance (already available from the nearby lookup) and
      a chosen speed/margin; make the speed and whether it's on a setting. Explore from
      real use — a too-aggressive filter that hides a train the user could have jogged for
      is worse than showing it (SPEC principle 1).

## Phase 3 — Full disruptions

Builds on Phase 1's minimal line-status marking.

- [ ] Stop/line disruptions (`/StopPoint/{id}/Disruption`, `/Line/{ids}/Disruption`)
      and cancellations of specific services where TfL exposes them.
- [ ] Rich in-app disruption text; mark a disrupted line/stop even when predictions look
      normal (D3). Domain summarization JVM-tested.

## Phase 4 — Widget

- [ ] Glance widget rendering from the persisted snapshot (no network on the render
      path); home-screen first.
- [ ] Lock-screen eligibility on Android 16 QPR (standard widget, no `not_keyguard`
      opt-out); one implementation for both placements.
- [ ] Refresh strategy: tap, host update, bounded periodic while plausibly visible (D5);
      staleness shown on the widget (D4).
- [ ] Roborazzi coverage of the widget layouts (normal, stale, offline, disrupted).

## Phase 5 — Distribution and polish

- [ ] Play internal-track deploy proven end to end; signing keystore via secrets.
- [ ] Fail a **release** build when the git-derived versionCode/SHA fell back (a
      source-archive or no-git build): Play rejects a non-incrementing versionCode, so a
      silent fallback of `1` is wrong for a shipped artifact. The derivation logs a
      warning now; the hard release-side guard lands here with the deploy job that makes
      release integrity meaningful (Codex, PR #4).
- [ ] Licenses / About screen finalized.
- [ ] Finalize the store-facing privacy disclosure (location, watched stops, the TfL
      requests) and the Play Data Safety answers — building on the debug-log disclosure
      that landed in Phase 1.

## Decisions needing review

- **Staleness threshold = 5 minutes** (`Staleness.THRESHOLD`, Phase 1 domain). The one
  shared "too old to trust" bound past which countdowns are withheld for "tap to refresh"
  (SPEC D4). Alternatives: a tighter 2–3 min (safer, but shows "tap to refresh" more
  often between routine refreshes) or a looser 10 min. Reversible — one constant, pinned
  by `StalenessTest`; change the value and the test together. Wants a look on a real
  device against real TfL refresh cadence.
- **`MainScreen` design guesses (all reversible; drive, MainScreen slice).** The screen
  landed on defaults worth a real-device look: **one flat soonest-first list across
  stops** rather than per-stop sections like the mock — SPEC's "soonest-first" wording
  drove it, but sectioned-by-stop is an easy alternative; a seed of **Oxford Circus +
  King's Cross St. Pancras** (public stations) until Phase 2 watched stops; a
  **10-second** on-screen clock tick for the countdown recompute; and a **three-kind
  error taxonomy** (offline / rate-limited / can't-reach-TfL) via a typed `TflException`.
  The row layout since settled on the **compact per-service card** (line pill +
  destination + merged countdowns, platform and stop name dropped — the row-merge item
  below records that redesign and the Phase-2 stop-name return), so it's no longer a guess
  here. None of the rest is load-bearing; all are cheap to re-shape once the flat list is
  seen on a device.
- **Per-stop snapshot: whole-screen stamp reads the *freshest* stop (PR #16, drive).**
  With per-stop ages, the top "updated N ago" stamp is ambiguous — chosen to be the
  newest stop's age (what "last refreshed" means), with per-row withhold carrying each
  stale stop's own truth, over the oldest stop's age (which would read "Tap to refresh"
  beside live countdowns). The mild understatement is bounded and made honest by the
  per-row "—". Also: `refreshFailure` fires only on a *total* failure (nothing fresh at
  all), `partialRefresh` when some stops refreshed and some were kept aged. All
  reversible — one stamp expression and two banner predicates. Wants a real-device look
  at a stop lagging behind its neighbors.
- **First screenshot job records + uploads only; no drift gate yet** (drive). CI proves
  the screens render (the `build` job runs them for pass/fail) and uploads the recorded
  PNGs, but does not yet fail on pixel drift or auto-commit the canonical set, because
  local and CI rendering can differ and the refresh apparatus isn't wired. The
  drift-refresh + visual-diff-comment follow-up is tracked under Phase 0. Reversible —
  adding the gate is additive.

## Decisions

- **Backup and device-to-device transfer of persisted config — DECIDED**
  (maintainer, 2026-09-18). Allow **both** Android cloud backup and device-to-device
  transfer; block neither. A phone swap keeps the user's watched stops, snapshot, and
  `app_key` (the fleet's "never lose the user's work" over a literal
  never-leaves-the-device wording). **Not MVP-scope work**: there is nothing to
  implement — the platform default already backs up and transfers, so no data-extraction
  rules and no `allowBackup=false`. Cost £0; no Play Data Safety change (Android Auto
  Backup is a platform feature, not data trackmo collects or transmits). Recorded in
  SPEC *Privacy*.
