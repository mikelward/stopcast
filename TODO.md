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

- [ ] Screenshot job (Roborazzi record + drift commit + visual-diff comment) — lands
      with the first screenshot test in Phase 1. This also covers the throwaway
      `HomePlaceholder`: Phase 1 replaces it with the real `MainScreen`, which arrives
      with its own Robolectric/Roborazzi coverage and the CI allow-list step. Standing up
      the whole record/drift/diff apparatus in Phase 0 for a placeholder that Phase 1
      deletes — and re-adding the Robolectric/Roborazzi deps trimmed here — is the infra
      this phase deliberately deferred (Codex, PR #4).
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
- [ ] Retain TfL's `direction` (inbound/outbound) on `TflArrivalDto` → `Departure` as the
      primary grouping key — it can't be reconstructed from destination/platform in general
      (a branch shares a direction) — then group departures into **(service, stop,
      direction) rows**: a pure domain grouping, soonest-first within each row, JVM-tested
      (D8). When TfL omits `direction`, the key falls back to platform then destination as
      a best-effort discriminator, so opposite directions stay apart whenever TfL gives any
      of those — a prediction with none of the three has nothing to key on and shares one
      "unknown" row.
- [ ] `MainScreen`: a **flat list, one row per (service, stop, direction)** (D8) over a
      seed set this phase — line, destination (the resolved destination the domain
      carries — `destinationName`, else `towards`), **platform where TfL gives one**
      (per departure — a row's countdowns can sit on different platforms),
      stop, and the next countdowns, with the "updated N ago" stamp and client-side
      countdown recompute. Ordered location-free (soonest-first). **Star-to-pin lands in Phase 2 with its
      persistence** — not here — so a star always survives restart rather than resetting
      (a half-persisted control loses the user's ordering). The compact swipe-card model
      is a later candidate too (SPEC D8).
- [ ] **Minimal disruption marking** — the honesty floor the first view can't ship
      without (SPEC principle 1 / D3); the *full* disruption experience is Phase 3:
  - `/Line/{ids}/Status` for the shown stops' lines: mark a departure whose line is
    disrupted, **and show a line's status even when it has zero predictions** (a
    suspended line often returns none) as a direction-independent status row, from the
    watched stop→line mapping retained independently of the predictions (SPEC
    *Departures* / *Disruptions*).
  - `/StopPoint/{id}/Disruption` for the watched stops: mark or suppress a **closed
    stop** even when its lines' status is normal — otherwise a closed stop shows
    valid-looking departures, the same quietly-wrong failure by a different path. A closed
    stop with **zero predictions** still surfaces as the same direction-independent
    status row (SPEC *Departures*), so the stop isn't dropped for want of a departure.
  - Handle a **partial refresh** (arrivals succeed, disruption lookup fails): keep the
    aged last-good disruption state or mark departures "status unknown" — never present
    them as verified-clean (SPEC *Disruptions*).
- [ ] **`docs/PRIVACY.md` describing the debug log's contents** — moved up from Phase 5:
      Phase 1 introduces the logger and Phase 0 the deploy pipeline, so a build carrying
      the log can reach testers now, and AGENTS.md requires the disclosure to exist before
      the log ships.
- [ ] **Persist the last-good snapshot; show a stamped placeholder at once and fill it
      in when the async read completes** (SPEC snapshot-render — never block the first
      frame on the DataStore read; the intro says the first deliverable exercises
      persistence). This is what gives the offline state something to show after process
      death — the two belong together, so snapshot storage lands here, not in Phase 2.
- [ ] Offline / rate-limited / error states rendered honestly (SPEC principles 1–2),
      backed by the persisted snapshot above.
- [ ] Unit tests for the domain; Robolectric + Roborazzi screenshot tests for the
      screen and its empty/offline/disrupted states, wired into the CI allow-list.

## Phase 2 — Watched stops and settings

- [ ] Add/remove **watched stops** (the source of truth for what's shown) — added from
      search or nearby discovery, removed explicitly; persist the set. Distinct from
      starring; removing a multi-line stop drops all its rows.
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
      filters, and key.
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
- [ ] (Later, open call) **Show every destination departing in the next ~30 min, else
      just the next** (D8) — a time-window rule for what a row/list shows: surface all
      distinct destinations with a departure inside the window, and fall back to only the
      single next departure when the window is empty, so a quiet stop still shows
      something. An alternative to a fixed "next few per row"; explore from real use.

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
