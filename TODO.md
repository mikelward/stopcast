# TODO

Phased plan toward the product in `SPEC.md`. Each phase lands as its own PR (or a small
stack), fully unit-tested, with `./gradlew test` and `./gradlew lint` green. Check items
off as they land; add newly discovered work to the right phase.

The ordering follows the maintainer's call that the **in-app view is the first
deliverable** (SPEC intro): the app screen is testable without the lock-screen host and
exercises the whole spine the widget later renders from.

## Phase 0 — Project scaffolding

- [ ] Gradle build: single `:app` module, Compose, kotlinx.serialization, DataStore,
      `mikelward/androidlog` as a resolved dependency (mirror simmo's
      `settings.gradle.kts` / `libs.versions.toml`).
- [ ] `minSdk 34`, `targetSdk`/`compileSdk` per the fleet, versionCode from the git
      commit count.
- [ ] `.claude/hooks/session-start.sh` to provision the Android SDK on web sessions.
- [ ] CI (`ci.yml`) mirroring the sibling fleet: build + unit tests + lint, the
      screenshot job (Roborazzi record + drift commit + visual-diff comment), and the
      deploy job (Play internal track, release notes from commit subjects).
- [ ] Shared checks wired: `lanes` (`.github/lanes.conf`), `codex` (the
      `mikelward/codex-review` workflows), `zizmor`.
- [ ] `AboutLibraries` licenses export + Licenses screen scaffolding.
- [ ] Green `./gradlew test` and `./gradlew lint`.

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
- [ ] **Exclude the persisted private data from cloud backup** (`allowBackup=false` or
      targeted data-extraction rules) as persistence lands, so watched stops, the
      snapshot, and the `app_key` don't leave the device via Android Auto Backup (SPEC
      *Privacy*).
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
- [ ] Licenses / About screen finalized.
- [ ] Finalize the store-facing privacy disclosure (location, watched stops, the TfL
      requests) and the Play Data Safety answers — building on the debug-log disclosure
      that landed in Phase 1.

## Decisions needing review

_(Autopilot records reversible guesses here — what was decided, the alternative, and
why it's reversible. Empty for now.)_
