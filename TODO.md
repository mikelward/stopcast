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

- [ ] TfL client behind a domain interface: nearby `/StopPoint`, `/StopPoint/{id}/
      Arrivals`; kotlinx.serialization models; recorded fixtures.
- [ ] Domain (pure Kotlin, JVM-tested): arrival→countdown formatting ("Due"/"3 min"),
      soonest-first ordering, expired-prediction drop (a countdown reaching zero leaves
      the list, never sticks at "Due"), a single shared staleness threshold, nearest-stop
      ranking.
- [ ] `MainScreen`: departures for the watched stops (a hardcoded/seed stop set is fine
      this phase), with the "updated N ago" stamp and client-side countdown recompute.
- [ ] **Minimal disruption marking** — the honesty floor the first view can't ship
      without (SPEC principle 1 / D3); the *full* disruption experience is Phase 3:
  - `/Line/{ids}/Status` for the shown stops' lines: mark a departure whose line is
    disrupted, **and show a line's status even when it has zero predictions** (a
    suspended line often returns none), from the watched stop→line mapping retained
    independently of the predictions.
  - `/StopPoint/{id}/Disruption` for the watched stops: mark or suppress a **closed
    stop** even when its lines' status is normal — otherwise a closed stop shows
    valid-looking departures, the same quietly-wrong failure by a different path.
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

- [ ] Pin/unpin stops; persistence via DataStore.
- [ ] "Near me now" discovery (on-demand location) with one-tap pin; stop search.
- [ ] Per-stop line/direction filters (D2).
- [ ] Optional user `app_key` in settings (D7).
- [ ] Extend the persisted snapshot (from Phase 1) to cover the watched-stop set,
      filters, and key.

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

## Open design questions

- **How to show each route's direction / destination in the departures view**
  (Phase 1–2, maintainer to decide). A stop/line usually runs two ways; how does a card
  present them? Options raised:
  - **Flat list** — every direction its own row. Simplest, fully glanceable, nothing
    hidden; but can get long, and the widget has little room.
  - **Swipe a card left/right** to change direction/destination. Compact, but hides state
    behind an interaction — weak for a glance surface, and a lock-screen widget can't
    easily be swiped.
  - **Configure per stop in settings** — builds on D2's per-stop line/direction filters
    (already planned); explicit, no hidden state, but setup effort.
  - **Star a card to bubble it to the top** — lightweight prioritization over the flat
    list.
  - **Smart selection** — add Home and Work and show whichever you're *not* near, and/or
    pick direction by time of day (morning → work, evening → home). Powerful, but
    location- and heuristic-dependent, and the widget is deliberately location-free at
    refresh time (D1) — a wrong guess is its own quietly-wrong risk.

  Lean (undecided): default to the **flat list honoring D2's per-stop direction/line
  filters**, with **starring to reorder**; treat swipe and the smart Home/Work +
  time-of-day behaviors as later enhancements once the basic view is on a device. Settle
  when the Phase 1 `MainScreen` and Phase 2 filters are built.

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
