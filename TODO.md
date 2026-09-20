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

- [ ] Screenshot job — record + upload landed; **drift-refresh + visual-diff apparatus
      wired**, awaiting one operator step. The `screenshot-tests` job now checks out the PR
      head branch, enforces the `--tests` allow-list against every `*ScreenshotTest`, clears
      then records, and fails on drift on pushes/forks; `sync-screenshots` (the
      `mikelward/ci-commit-artifact@main` reusable workflow) pushes the refreshed PNGs back
      to a same-repo PR branch; `post-screenshot-diff` posts the before/after PR comment.
      **Remaining: `repo setup` must be re-run after this lands on main** — it only
      provisions `CI_COMMIT_ARTIFACT_TOKEN` (in a `ci-commit-artifact` environment) once a
      default-branch workflow actually calls the reusable workflow, so on the PR that adds
      this `sync-screenshots` fails for lack of the token (deliberately kept out of the
      required `lanes` gate so it doesn't block the merge). Also note: the first main push
      after merge may show screenshot drift red on `lanes` if the committed baselines differ
      from the CI render — the next UI PR's `sync-screenshots` commits the CI-accurate set
      and self-heals it once the token is in place.
- [x] Deploy job (Play internal track, release notes from commit subjects) — **pipeline
      landed**: `release-apk` (PR-lane R8 smoke test), `release-build` (signed AAB) and
      `deploy` (GitHub prerelease + Play internal-track upload, notes built from commit
      subjects) in `ci.yml`, plus `scripts/publish-github-release.sh` and its two PR-run
      tests, `workflow_dispatch` deploy-force, and `dev-docs/play-store-internal-track.md`. No
      Firebase (dropped every google-services/Crashlytics step). **Human setup still owed
      before a build actually ships** (all in `dev-docs/play-store-internal-track.md`): generate
      the upload keystore; create the `app.trackmo` app on Play Console and seed the internal
      track with one manual upload; create the Play service account; add the five secrets
      (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`,
      `RELEASE_KEY_ALIAS`, `PLAY_SERVICE_ACCOUNT_JSON`) to a `production` environment
      restricted to `main`; and complete the Play Data Safety / App content form. End-to-end
      proof is the Phase 5 item.
- [x] `AboutLibraries` licenses export + Licenses screen scaffolding. The plugin exports
      the transitive dependency graph to `res/raw/aboutlibraries.json` (committed;
      regenerated with `./gradlew :app:exportBundledLicenses`, since AGP 9 can't wire the
      resource at build time), and a `LicensesScreen` renders it — reached from an About
      dialog behind the top-bar overflow menu. Roborazzi-covered.

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
- [x] **`docs/PRIVACY.md` describing the debug log's contents** — moved up from Phase 5:
      Phase 1 introduces the logger and Phase 0 the deploy pipeline, so a build carrying
      the log can reach testers now, and AGENTS.md requires the disclosure to exist before
      the log ships. Landed: `docs/PRIVACY.md` is the source of truth for what leaves the
      device (the TfL requests the product needs, the optional user `app_key` → TfL if set,
      and the platform backup/transfer channel that carries persisted config including the
      key — no off-device channel trackmo adds beyond TfL), what the on-device log carries
      (coarse stop/line IDs, HTTP status, location fix outcomes — never a coordinate or
      key), and that a shareable export redacts travel data. See the doc for the precise,
      canonical wording — this line is a pointer, not a second inventory to keep in sync.
  - [ ] **Wire the shared on-device logger into both DataStore corruption handlers**
        (`DataStoreSnapshotStore` and `DataStoreWatchedStopsStore`) when it lands, so a
        discarded snapshot or watched-stop set is never silent (SPEC principle 2 / *never
        fail silently*). Today both default `warn` to a no-op — the seam is there but the
        shared logger isn't yet — so on genuine corruption the file is discarded without a
        diagnostic. The **watched-set** discard is the higher-stakes one: it loses the
        user's own config, not a re-fetchable cache (though the set also rides Android
        backup, so it isn't the only copy). Codex P2 on PR #26.
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

### Phase 1 — corrections to shipped departure-label rendering (follow-up)

Corrections to the departure label the card already ships (the via-branch and the
tight-width abbreviation); the Phase 4 widget mirrors the same shared rendering, so each
fix lands in the shared layer, not per-surface. Raised in chat 2026-09-19.

- [ ] **Only show the via-branch when the trunk diverges downstream of this stop, in this
      direction.** The shipped via-branch (#48) shows TfL's `towards` "via X" whenever TfL
      gives one, which is meaningless when the trunk is *behind* the rider — e.g. "High
      Barnet via Bank" northbound, where Bank is south of the junction. Maintainer's rule:
      the via matters at Highgate *southbound* (towards Morden / Charing Cross / Kennington /
      Battersea — the Camden Town trunk choice is ahead) but **not northbound** (towards High
      Barnet — behind you). **Approach: start cheap** (maintainer, 2026-09-19) — a
      data-driven heuristic: show the branch on a line only when the same (line, direction,
      **terminus**) at this stop actually has **≥2 distinct branches** in the shown departures
      (i.e. it disambiguates two trains *to the same terminus* you could catch). Different
      termini are already distinguished by the destination, so they get **no** via — Mill Hill
      East vs High Barnet needs no "(via …)", only same-terminus trunk splits (Morden via Bank
      vs via Charing Cross) do (maintainer, 2026-09-19). Do **not** build the route-sequence
      topology version (`/Line/{id}/Route/Sequence`) — even that wouldn't match TfL's own
      practice. Put the relevance filter in whatever shared per-(destination, branch)
      grouping the card and widget both render from, so they can't diverge — today that
      grouping is a local value in `MainScreen`; PR #44 promotes it to a pure domain helper
      (`DepartureRows.destinationLines`) the widget reuses. If this lands before that helper
      exists, add it as the pure domain function the app uses now and any later widget must
      reuse (don't leave it card-only). Supersedes the naive unconditional display.
- [ ] **Branch truncation: abbreviate across both halves, keep one full word in each**
      (maintainer, 2026-09-19). Rendering "Destination (Branch)" at decreasing width, don't
      spend the space keeping one half fully spelled while gutting the other; instead
      abbreviate words across both and **preserve at least one full (unabbreviated) word per
      half**. So "Battersea (Charing X)" — "Battersea" whole in the destination, "Charing"
      whole in the branch with only "Cross"→"X" — beats both "Battersea Power (CX)" (branch
      gutted) and "B.P.S. (Charing Cross)" (destination gutted). Branch word-replacements are
      per `abbreviateBranch` ("Cross"→"X", "East"→"E.", "North"→"N.", "Central"→"C."); "Charing
      X" is the friendly form and beats "Charing…", so word-abbreviate before any ellipsis, and
      the "CX" initialism (both words gone) is only a last resort "if necessary" when even one
      full word per half won't fit. This is close to shipped (full-or-word-abbreviate; the
      widget always abbreviates since it can't measure) — the follow-up is the
      one-full-word-per-half allocation on the in-app card, the CX/ellipsis last-resort rung,
      and a real-width device check. (An earlier note said "prefer plain ellipsis" — backwards.)
      Names with no mappable word (High Barnet, Walthamstow Central once "Central"→"C." is
      spent, Battersea Power Station) can only ellipsize, keeping the most recognizable word.
      **On-device confirmation (2026-09-19, maintainer screenshot):** the shipped card renders
      "Battersea Power S… (CX)" where it should render "Battersea… (Charing X)", and
      "High Barnet (CX)" where "High Barnet (Charing X)" fits — i.e. it currently keeps the
      full destination and initialises the branch, the exact inversion this item fixes.

## Phase 2 — Watched stops and settings

- [ ] Add/remove **watched stops** (the source of truth for what's shown) — added from
      search or nearby discovery, removed explicitly; persist the set. Distinct from
      starring; removing a multi-line stop drops all its rows. **[store landed, PR #26]**
      `WatchedStop`/`WatchedStops`/`WatchedStopsStore` + `DataStoreWatchedStopsStore`
      (reactive `watched()`, add/remove; a newer-schema file reads as `Unavailable` and is
      preserved, never overwritten). Still to wire into `MainViewModel` as the departures
      source and build the add/remove UI.
  - [ ] **Version-envelope read before a future incompatible schema bump** (Codex P2 on
        PR #26, deferred). The store preserves a newer file that still *decodes*, but a
        future schema that changes an existing field's shape would fail to decode and be
        discarded as corruption — silently deleting the set on a downgrade. Before shipping
        any schema **v2+**, add a stable version-envelope read (parse `version` alone; keep
        unknown-version raw bytes rather than treating a decode failure as corruption).
        Best built *with* that v2 (its shape is needed to build and test it); v1 is the only
        schema today, so a decode failure now is genuine corruption and correctly discarded.
- [ ] **Restore the stop name to the departure card when the list spans more than one
      stop.** The compact-card redesign dropped it (too much clutter, and implicit on a
      single-stop widget), but the current seed is already multi-stop (Oxford Circus +
      King's Cross), so a card gives no boarding location and a countdown can't be told
      apart from the other station's (SPEC D1 / principle 1). Accepted as an MVP
      limitation on the demo seed (maintainer, ship-MVP call); once real watched stops
      land, show the stop (a subline, or only when >1 distinct stop is on screen) so
      cards stay unambiguous. Codex P1 on PR #17 (`discussion_r4049648510`).
- [x] **Star** rows to reorder them to the top — ranking only, not membership; persisted
      via DataStore so a star survives restart (D8). Keyed by the full
      **`(stop, service, resolved direction key)`** identity (`DepartureRow.directionKey`:
      direction, else platform, else destination), so a star restores to exactly one row.
      Landed: `StarredRow`/`Starred` + `DepartureRows.pinStarred` (warnings still lead —
      a starred service never jumps above a closure or a no-prediction status row),
      `StarredRowsStore` + `DataStoreStarredRowsStore` (reactive `starred()`, toggle; a
      newer-schema file reads as `Unavailable` and is preserved, never overwritten), the
      per-card star control (filled `Star` in primary when on, the vendored `StarBorderIcon`
      outline when off), and `MainViewModel.toggleStar`/`starred` wired through `MainScreen`
      and `MainActivity`. Stars ride Android backup/transfer (SPEC *Privacy* backup note),
      not an app-initiated send. The control shows only on timed cards — a star restores its
      pin the moment a starred, currently-suspended line has departures again.
- [ ] "Near me now" discovery (on-demand location, nearby `/StopPoint` lookup selected by
      `NearbySelection`) with one-tap add-to-watched; stop search. Distance ranking lives
      here — for *finding* stops to watch — not in ordering the watched list, which stays
      location-free so the view works with location denied (D1). Stop **selection** (all
      services within ~0.2 mi + the nearest stop of each mode within ~1 mi, distance-sorted,
      no count cap) is implemented in `NearbySelection` (PR #39); one-tap add-to-watched and
      stop search are still to build.
  - [ ] **Nearby selection — the still-being-shaped policy toward SPEC's constraints.** The
        *settled* product constraints — a line appears once (not per stop it passes), no dense
        mode crowds out another, bounded within reach, by line with both directions, closed
        stops surfaced honestly — now live in **SPEC *Finding stops → Near me now*** as the
        product intent the implementation must satisfy. This item is only the experimental
        policy that meets them (maintainer, on-device 2026-09-19). The symptom that started it:
        the current "nearest N stops" set repeats the same bus line several times, one per stop
        it passes.
    - Current lean (maintainer): **all services within ~0.2 mi, plus at least one stop per
      mode that has a stop within ~1 mi**, expanding the 0.2 mi radius if nothing falls
      inside it. A fixed total (an earlier "nearest ~8 services" idea) is de-emphasized in
      favor of this distance-shaped rule. Still illustrative, not settled — try and see.
    - **Open: does the list need a hard count cap at all?** Not decided (maintainer,
      2026-09-19 — "that's what a to-do means"), and the lean is **away from an arbitrary cap**.
      The worry against one: at a busy junction a count cap would **silently hide options** the
      user might want — which is exactly SPEC principle 2's "never hide options quietly," so an
      arbitrary numeric cap is the wrong default. Plan: **try the distance-shaped rule on a real
      device first** and see whether a dense interchange actually produces an unscannable list;
      only reach for some bound if it does, and even then not a bare number that drops services
      with no signal. If any bound is added it must **reserve the per-mode entries first** (the
      crowd-out rule) and not silently discard relevant nearby services. Whether-and-how is part
      of the on-device shaping, not settled here or in SPEC.
    - Candidate that avoids silent hiding (maintainer, 2026-09-19): rather than dropping rows,
      **collapse above a per-mode threshold behind an expander** — e.g. "Tap to see 5 more buses"
      — so a busy junction stays scannable but every option is still one tap away, nothing
      hidden. Just a direction to try later, not decided.
    - **One canonical distance unit:** miles (the maintainer's numbers are in miles) — the
      current outer ring is ~1 mile (~1609 m) and the inner ring ~0.2 mi (~322 m). The ~1 mile
      is the TfL query's own radius (the hard reach for one lookup); the expand-if-empty rule
      applies to the *inner* ring — when nothing is within ~0.2 mi the selection keeps the
      nearest stop that is still within the ~1 mile query. **These radii are provisional
      — the maintainer has not tested them on a device — so nothing here is settled; the numbers
      are what `NearbySelection` currently uses, to be tuned once tried in the field.**
    - The natural unit may be a "mode-stop" (all services at a nearby stop); how stops /
      mode-stops / directions map onto the TfL model and API is an **implementation** question
      left open here — this bullet is the policy, not the settled shape (which is in SPEC).
  - **Near-me-now display order — closest stop first (maintainer, 2026-09-19, supersedes the
    two-band lean below).** The near-me list now sorts **by stop distance, closest first**
    (`DepartureRows.byStopDistance`), with **soonest-first only as a same-stop tiebreak** (rows
    at one stop are equidistant, so time orders them; it never moves a near stop below a far
    one). Warnings still lead (by `rank`) and starred rows are lifted afterward by `pinStarred`.
    Applies only on the near-me path (distances present); the location-free watched list keeps
    `across`'s soonest-first order (D1). A stop missing from the distance map sorts last.
    - **Why, and why "starting point":** closest-first matches "what can I walk to from here,"
      and — the incidental win — it **cuts reshuffles**: distance is near-constant between
      refreshes, so a stop's rows stay grouped and only re-order *within* the stop as
      countdowns tick, instead of the whole list re-interleaving across stops every tick like
      global soonest-first did. Accepted cost: the closest stop leads even when nothing leaves
      it soon (a far stop's imminent departure sits lower) — judged acceptable to **try on a
      device** and revisit. Not bucketed yet, so fine GPS jitter can still swap near-equal
      stops; coarse-bucketing distance is the first lever if that reads as churn on-device.
    - **Superseded (earlier lean, kept for the reasoning):** a two-band order — inner-ring
      (~0.2 mi) rows first, mode-coverage rows (pulled in from beyond ~0.2 mi) below — so a far
      coverage Tube/pier never floats up on a soon countdown. Closest-first achieves the same
      "far coverage stop doesn't jump the queue" outcome without a threshold to tune, so the
      band split is dropped as the starting point; revisit only if pure distance reads worse on
      a device than banding would.
- [ ] **Search for a stop by name or line, and pin it.** Beyond nearby discovery, let the
      user type a **stop/station name** (TfL `/StopPoint/Search`) *or* a **line**
      (`/Line/Search/{query}` — the query is a path segment, not a `?query=` parameter like the
      stop search — then that line's stops via `/Line/{id}/StopPoints`) — SPEC *Finding stops*
      requires both
      discovery paths, so a user who knows the line but not a stop name isn't stuck. Pick from
      the matches and add to watched — for stops they care about that aren't near them now
      (home, work, a regular destination). Complements "near me now" and star-to-pin.
      **Privacy:** the typed query is sent to TfL's search endpoints — a new off-device input
      beyond today's coordinates/stop-IDs, so SPEC *Privacy* is updated to disclose it (done)
      and the Play Data Safety answers account for it when this ships. Requested 2026-09-19.
- [ ] **Re-locate the "near me now" list on demand, not just on first open.** Today the
      nearby flow locates once; a user who has moved is stuck on the old position until
      something else re-triggers it. **The toolbar button shipped in PR #43** (the crosshair
      "Stops near me" action, left of Refresh), but it calls the plain `locate()` path — so
      it still needs the **force-fresh** mode below. Codex flagged this on #43
      (`discussion_r4053414081`, deferred here 2026-09-19): tapped within ~2 min of an
      accurate fix it re-queries TfL with the *cached* (old) coordinates via
      `FixSelection.resolve()`, the very staleness this item exists to fix. **First
      deliverable: an explicit "Update location"
      toolbar button** — the standard my-location glyph (crosshairs / GPS arrow) — that
      re-runs the fix and the nearby lookup. Keep re-location behind that deliberate
      near-me action (and other explicit near-me moments, e.g. returning to the discovery
      screen after a gap) — **not** folded into the departures **refresh** path: D1 keeps
      location off every refresh, and the `StopFinder`/`TflClient` split enforces that
      boundary. It reuses the `AndroidLocationProvider` path but needs an **explicit-update
      mode that forces a fresh fix**: `FixSelection`'s fast path returns a cached fix up to
      `FRESH_ENOUGH_MILLIS` (~2 min) old without calling `freshFix`, so a plain re-run tapped
      within that window would reuse the old coordinates — the very staleness this item fixes.
      The Update-location action must attempt a fresh fix first, applying the bounded cached
      fallback only if that fails. Each re-location is another on-demand coordinate to TfL —
      the same recipient and data category as the first fix, not a new off-device channel — so
      £0, negligible against the keyless rate budget for a user-initiated tap, and a small
      per-fix battery cost.
  - [x] **Nearby per-mode coverage (crowd-out)** — landed, PR #39. The nearby list shows a
        line once from its nearest stop (dedupe, PR #36) and now mixes in the nearest stop of
        each mode within ~1 mi so a denser mode can't crowd out another — the one Tube within
        reach no longer falls outside the nearest bus stops and vanishes. Distance-shaped, no
        fixed count cap (SPEC *Finding stops → Near me now*; the cap stays parked in
        `NearbySelection`).
  - [ ] Bound the nearby request burst at a **dense interchange** without a count cap.
        `MainViewModel.refresh()` fetches arrivals per selected stop (~2 requests each) plus a
        line-status call, every minute; with 25+ stops in the ~0.2 mi inner ring that can
        approach TfL's ~50 req/min keyless budget and get persistently rate-limited (Codex,
        PR #39). Left uncapped for now — the maintainer's no-count-cap decision stands (a cap
        silently hides options, SPEC principle 2), and a typical locate is a handful of stops;
        keyless degrades honestly on 429. Revisit only if a real dense interchange proves a
        problem on-device, and then without dropping services silently: options are a soft
        stop-fetch budget that reserves the per-mode entries first, scaling the refresh
        interval with the set size (a staleness trade), or leaning on the optional user
        `app_key` (D7, ~500/min) for dense areas.
  - [ ] Use measured `Location.accuracy`, not just provider name, on **both** the cached
        fast-path and the fresh-fix waterfall. `AndroidLocationProvider` classifies a fix as
        accurate by provider (GPS/fused, PR #38), which is a proxy: a fused fix derived from
        Wi-Fi/cell can be less accurate than an older GPS fix. So on the cached fast-path a
        recent fused fix can short-circuit, and on the fresh waterfall a prompt fused fix is
        accepted, both without preferring a more accurate GPS fix (Codex, PR #38, both paths).
        Deferred because it is below the resolution the fix targets — #38 exists to stop a
        ~1 km network fix reading a stop half a mile away as nearest, and a fused fix is tens
        of meters, ample for the nearest stop — and choosing an accuracy threshold (what
        counts as "insufficient", whether to wait for GPS and how long) is device-tuning the
        sandbox can't validate and a change to the maintainer-approved provider-name design.
        Preserve `Location.accuracy` through both paths and prefer the most accurate fix;
        settle the threshold and the latency trade on a device.
- [ ] Per-stop line/direction filters (D2).
- [ ] **Filter or rank by a destination the user enters, and let them save favorite
      destinations** — the user names where they're going (or picks a saved favorite) and
      trackmo surfaces the rows that get them there, complementing starring. Scope it to
      **on-device matching** against each row's retained destination text, so the
      destination is never sent to any network service. (The resolved destination and now
      the via-branch (`Departure.branch`) are both retained, so matching can key on either;
      the raw `towards` — the comma tail of a bus destination like "Pimlico, Grosvenor
      Road", or the downstream branch/interchange label below — is still not kept, so a cut
      that needs those must retain them first.) Saved favorites persist like
      the rest of the user's config and so ride the platform backup/transfer — the
      platform channel, not an app-initiated send, and already covered by SPEC *Privacy*'s
      backup note. An
      off-device "does this stop reach X" lookup (TfL Journey Planner) would transmit the
      destination to TfL and is a **separate product + privacy decision** (SPEC declares
      the Journey API a non-goal; it would change the Play Data Safety answers), not
      assumed by this item.
- [ ] **Working hours / trip windows** (requested 2026-09-19, on-device). Let the user say
      when they commute (a morning window toward work, an evening one home), so trackmo can
      emphasize the relevant direction at the relevant time and scope commute announcements
      (Phase 3) to those windows. Matching stays on-device, tied to favorite destinations
      above; the windows persist with the rest of the config and so ride Android backup /
      device-to-device transfer — the platform channel covered by SPEC *Privacy*'s backup
      note, not an app-initiated send (so not "on-device only"). Design the model and where
      it surfaces before building.
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
- [x] **Show the via branch beside the destination** — TfL's `towards` "via" trunk
      ("Battersea Power (Charing Cross)") shown parenthesized after the terminus, so a
      rider can pick a branching-line train (Northern most visibly). The branch
      **participates in grouping**: within a direction a line splits per terminus *and*
      branch, so two trains to one terminus via different trunks each get their own line
      and countdown (a countdown never sits under the wrong branch). The **branch outranks
      the terminus for space**: where the pair won't
      fit, the destination truncates and the branch shortens to the board's own form
      (Cross → X, East → E. &c.), measured against the row so the full branch still shows
      where it fits — "Batter… (Charing X)" in the tight case (SPEC). Follow-ups: **eyeball
      on a device** the truncation balance and the abbreviations, and whether `spaced-slash`
      ("Battersea Power / Charing Cross") reads better than parens — an open alternative.
- [ ] (Later, open call) **One row per destination** as an alternative grouping to
      (service, stop, direction) (D8) — every row then names a single unambiguous
      destination (and handles a blank `direction` via `towards`), at the cost of more
      rows for a branching line. Explore against the shipped keying from real use.
- [x] **Per-line Overground pills.** The 2024 named Overground lines (Lioness, Mildmay,
      Windrush, Weaver, Suffragette, Liberty) each show in their own line color, rendered as
      a **hollow** pill (surface fill, line-color border + label, nudged for contrast on the
      surface) — this supersedes the earlier two-color banded idea: the hollow shape tells
      Overground apart from a same-color tube line and matches how TfL draws it (PR #42,
      SPEC). Follow-up: confirm the six hex values against TfL's colour standard (it isn't
      reachable from the build environment).
- [ ] (Later, open call) **Show every destination departing in the next ~30 min, else
      just the next** (D8) — a time-window rule for what a row/list shows: surface all
      distinct destinations with a departure inside the window, and fall back to only the
      single next departure when the window is empty, so a quiet stop still shows
      something. An alternative to a fixed "next few per row"; explore from real use.
- [x] **Fixed-width line pills** — every pill now shares one fixed label width, sized to
      the widest code (a four-character bus route), so they form a tidy column instead of
      ragged-width blobs (equal-width-line-chips). Follow-up: eyeball the column and the
      widest codes on a device — the width is verified by unit test but not yet seen on
      real hardware.
- [ ] (Later, open call) **Colors and codes for National Rail services.** When rail
      departures land (National Rail / Thameslink item below — TfL's feed doesn't carry them
      today), give each operator its own pill instead of the neutral fallback (Google Maps,
      for one, shows Southern as a green fill with yellow text). And rethink the abbreviation:
      the shipped first-three-letters code collides for multi-word operators (Southern and
      Southeastern both → "SOU"), so use the official two-letter TOC (train operating
      company) codes for National Rail operators — SN Southern, SE Southeastern, SW South
      Western, TL Thameslink, GN Great Northern, GX Gatwick Express. National Rail only: the
      Elizabeth line and Overground keep their current pills — their TOC codes (XR, LO) read
      worse than what they already show ("ELI", and the named-line hollow pills).
- [ ] (Later, open call) **Revisit auto-locate-on-open and the location states.** Trackmo
      resolves location once on open (a `LaunchedEffect` gated on `PermissionRequired`) and
      the nearby set never re-resolves afterward except via the temporary crosshair button.
      Work out the intended behavior across the states — first open, permission
      granted / approximate-only / denied / permanently-denied, returning after moving,
      returning from Settings, a stale fix — and whether re-locating should be automatic (on
      resume, on a significant move) rather than a manual tap. The crosshair button is a
      stopgap for on-device radius testing until this is settled.
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
- [ ] **Tap a card to open a detail view** (requested 2026-09-19, on-device). The compact
      card drops platform, full direction, and any longer disruption text to stay glanceable
      (SPEC *Departures*); a tap opens the fuller picture — platform and direction (already in
      the domain), longer disruption text, and, if a source exists, **accessibility** info
      (step-free, lifts out of service). Open design questions to settle before building — not
      specified here: where accessibility data comes from (today's TfL surface — arrivals,
      line status, free-text stop disruptions — distinguishes no lift/step-free state, so a
      source has to be found, or the feature drops it); how the view renders honestly
      (from state already in memory, not a tap-time fetch) and how each source's freshness is
      tracked, since arrivals, disruption, and any accessibility data age independently and a
      safety-relevant lift outage must never read as current when it isn't (D4 / principle 1).
- [ ] **Hand off to a navigation app** (requested 2026-09-19, on-device). From a stop (likely
      the detail view above), let the user open the stop in Google Maps or their default nav
      app — a geo/maps intent to the stop's coordinates or name. No new dependency (a plain
      platform intent), but **it does cross a privacy boundary**: the receiving app is
      user-chosen and usually cloud-backed, so the stop's coordinates/name reach that third
      party — a **Play Data Safety** consideration (a new recipient of location-adjacent data),
      at **$0** cost with the reliability/behavior of whatever app the user picked. Because
      the user explicitly initiates the hand-off to an app of their choosing it's a
      lighter-weight decision than a silent send, but the Data Safety consequence is named
      here rather than assumed away. Decide the intent shape and entry point.

## Phase 3 — Full disruptions

Builds on Phase 1's minimal line-status marking.

- [ ] Stop/line disruptions (`/StopPoint/{id}/Disruption`, `/Line/{ids}/Disruption`)
      and cancellations of specific services where TfL exposes them.
- [ ] Rich in-app disruption text; mark a disrupted line/stop even when predictions look
      normal (D3). Domain summarization JVM-tested.
- [ ] **Classify disruption kind, and judge relevance** (reported 2026-09-19, on-device). A
      small diversion currently surfaces as "Special Service" where a rider expects "Detour"
      or "Diversion". Open design point for when it's built: today's label is the generic
      `/Line/{ids}/Status` `statusSeverityDescription`, which carries no discriminator for the
      cause, so a rider-readable kind needs a detailed source (`/Line/{ids}/Disruption` or
      similar) — settle the source, mapping, and fixtures then. This also decides the wording
      question the maintainer raised (2026-09-19): "Special Service" (severity 0) and "Diverted"
      (15) are distinct TfL statuses shown verbatim, so a small severity→copy map can't tell a
      real diversion from an unrelated special service — relabeling terse statuses into clearer
      rider copy needs the same detailed source, not a severity guess. And decide **whether to
      show a disruption at all** when it's not relevant to most journeys through the stop (the
      observed case was a detour miles away), with any relevance test still erring toward showing
      over hiding (SPEC principle 1 — a wrongly-hidden real disruption is worse than an extra one).
- [ ] **Make the disruption chip lighter-weight than a full row** (requested 2026-09-19,
      on-device). The shipped "Special Service" (and other status) chip takes a whole row,
      which reads as too heavy for what it conveys — the maintainer suggested a warning
      triangle (or similar compact affordance) instead. Refines the shipped Phase 1 status
      chip's density without dropping the signal (SPEC principle 1/2 — the disruption must
      still be visible and, ideally, tappable to the fuller detail once the Phase 2/3 detail
      surface exists). Design the compact form before building. **Constraint:** the glance
      surface (the widget) must still show SPEC's one-line disruption summary + count
      (`SPEC.md` *Disruptions*) — a screen-reader-only label or an optional tap doesn't satisfy
      sighted at-a-glance use, so icon-only is a **card** option; on the widget the compact
      icon accompanies the visible summary rather than replacing it.
- [ ] **Commute disruption announcements, without being noisy** (requested 2026-09-19,
      on-device). Notify the user of a disruption to *their* commute — a watched line/stop on
      the routes they take — but only when it matters: scoped to their working-hours / trip
      windows (Phase 2 favorite-destinations item) and de-duplicated so an ongoing disruption
      doesn't re-notify. The whole design turns on not crying wolf; a notification is a battery
      and attention cost, so this is a product + battery decision, not a quiet add (SPEC *Cost
      and reliability*). **It also needs a background-refresh mechanism the current model
      doesn't have**: SPEC only polls while the app is open (plus opportunistic widget
      refresh), so a closed-app trip window can't discover a new disruption to announce.
      Designing that means naming the periodic worker (e.g. `WorkManager`), its wakeup/request
      cadence, the added TfL rate-limit pressure, and the stale/error behavior — record those
      before implementing, so this doesn't ship as either a nonfunctional alert or unplanned
      background polling. **Cost £0** (the worker's TfL polls carry the same watched stop/line
      IDs to the same recipient, no new service or key), so likely **no Play Data Safety change**
      — same recipient and data categories as the on-demand departures fetch — confirmed when
      built; the real costs are battery and TfL quota, above. It also needs the runtime **notification permission** (Android 14+):
      an opt-in request and an explicit denied-state behavior — don't run the worker (burning
      battery and TfL quota) while every alert is invisible — with the permission behavior
      recorded in SPEC.
- [ ] (Later, open call) **National Rail / Thameslink departures** (recorded 2026-09-19;
      the maintainer asked to note it and not build it now). TfL's Unified API arrivals
      cover tube, Overground, Elizabeth line, DLR, tram, bus and river bus only — **not**
      National Rail or Thameslink heavy-rail services. Showing those means a **second,
      separate data source** (National Rail's Darwin feed), which is a new external
      dependency **and** a Play Data Safety change (a new off-device request), so it's a
      distribution + product-scope decision, not an implementation detail. The fitting
      interface is **OpenLDBWS** — request/response, so it slots into trackmo's existing
      poll-on-demand snapshot/refresh model (D5) with no extra runtime cost beyond the
      request itself. **Cost: £0** — OpenLDBWS is free with registration (National Rail
      open data), rate-limited. Reliability: a new point of failure and added latency vs.
      TfL alone, and a separate token to keep valid. (The **Darwin push port** streams
      continuously and does **not** fit this model — it would need an always-connected
      on-device consumer, with its own wakeup/battery cost, or a relay service, with
      hosting cost, another dependency, and added privacy exposure; so it is not the £0
      path and not the default here.) Awaiting the maintainer's go-ahead before any build
      work; confirm the current OpenLDBWS registration terms and limits when it's picked
      up.

## Phase 4 — Widget

- [x] Glance widget rendering from the persisted snapshot (no network on the render
      path); home-screen first. Tap opens the app; stamp + stale note per D4. `widgetModel`
      (the render decision) is pure and unit-tested; the layout is node-tested via Glance's
      unit-test harness (see below). A device eyeball of the real rendering is still owed.
- [x] Lock-screen eligibility on Android 16 QPR (standard widget, no `not_keyguard`
      opt-out); one implementation for both placements. `widgetCategory="home_screen|keyguard"`
      in the provider info — placement needs a real Android 16 QPR device to confirm.
- [~] Refresh strategy beyond app-driven push (decided 2026-09-19 — see *Widget follow-ups*
      below). The app calls `updateAll` on every fetch, so the widget follows the app's last
      refresh; `updatePeriodMillis=0`. The **honesty** half landed in #44: the widget
      schedules one render-only redraw at its staleness boundary (a `WorkManager` one-shot per
      snapshot), so a closed-app widget flips to the stale `?` treatment on its own instead of
      holding a live-looking countdown forever (SPEC D4). The **data-refresh** opt-in landed
      too: the "refresh widget every minute" setting drives a self-rescheduling WorkManager
      one-shot chain (mechanism B), off by default, for the screen-on kiosk case. What still
      remains: **refresh-on-unlock by default** (`ACTION_USER_PRESENT`), and the
      screen-**off** guarantee via a foreground service (mechanism A) — see *Widget
      follow-ups*.
- [x] Layout coverage of the widget states, two complementary forms. `WidgetContentTest` uses
      Glance's own unit-test harness (`runGlanceAppWidgetUnitTest`, under Robolectric for a real
      `Bundle`) to assert the emitted layout nodes (no-data, no-rows, stale-empty, fresh-row,
      branching, via-branch, stale-withheld). `WidgetScreenshotTest` **pixel-captures** the widget
      by rendering it to RemoteViews with `GlanceRemoteViews.compose` and inflating them to a
      `View` (fresh light/dark, stale, empty), so clipping/sizing/color regressions are caught —
      recorded via its own `--tests` allow-list step in the `screenshot-tests` job. An on-device
      eyeball of the real host rendering is still owed.
- [x] **Widget parity: starred rows pinned, and per-(destination, branch) lines** (the A/B/C
      "how faithfully the widget mirrors the in-app list" decision — maintainer chose full
      parity, 2026-09-19). `provideGlance` now loads the starred store (off the render path)
      and `widgetModel` applies `DepartureRows.pinStarred` before the cap, so a starred service
      past the six-row cap is lifted to the top (SPEC D8) instead of dropped. `WidgetRow` now
      renders per-(destination, branch) lines via the shared `DepartureRows.destinationLines`
      (used by the in-app card too, so the two surfaces can't drift), replacing the
      headline-only filter that dropped a branching row's divergent destinations; the widget
      shows the via-branch in the board's short form (`abbreviateBranch`, since Glance can't
      measure width). **Closest-first / nearby-dedupe ordering is NOT part of this** — it stays
      the deferred follow-up below (needs distances the snapshot doesn't carry, and is moot once
      Phase 2's watched stops replace the interim nearby source).
- [ ] Widget feeds off the interim *nearby* set (the last stops the app fetched), via a
      save-only `WidgetSnapshotStore`. Replace with Phase 2's user-chosen watched stops so
      the widget shows a stable set rather than "wherever you last opened the app".

### Widget follow-ups (decided with the maintainer 2026-09-19)

The widget review surfaced findings that push against the **persisted snapshot's deliberate
design** (`DeparturesSnapshot` KDoc: it carries *only* the honest last-good departures and
each stop's age — never transient disruption/line-status or refresh-failure state, because a
persisted point-in-time closure or line status ages into a claim we can't stand behind).
The maintainer settled each; #44 ships the render surface with the honest fixes (per-row stale
withhold, explicit empty states, fresh-before-truncate cap, corruption logging, layout tests),
and these carry the rest as their own PRs:

- [~] **Widget refresh (own PR).** The opt-in **"refresh widget every minute" setting (off
      by default)** landed via **mechanism B** — a self-rescheduling WorkManager one-shot
      chain (`WidgetRefreshWorker`) that re-fetches the widget's persisted stops ~1/min and
      saves the snapshot, holding while the screen is on and deferred by Doze otherwise. That
      covers the screen-on kiosk/home case. Two pieces still remain:
      - [ ] **Refresh on unlock by default** (`ACTION_USER_PRESENT`, a manifest receiver —
        one fetch when the device is unlocked; battery-negligible because it piggybacks on
        active use rather than waking the radio from idle; cellular data is the only real cost,
        ~2–5 MB/day, gate on WiFi/charging if wanted). This is the default path for a user who
        never opts into the every-minute loop.
      - [ ] **Mechanism A — foreground service, the screen-off follow-up** (recorded as the
        maintainer asked: *start with B, record A as a possible follow-up if B doesn't work*,
        2026-09-20). B is Doze-deferred, so it does **not** guarantee the exact minute with the
        screen off; a foreground service would, at the cost of a persistent notification, the
        Play foreground-service-type policy that carries (the snoozemo precedent), and more
        battery. Take this only if the screen-on case proves insufficient on a real device.
      - Motion-triggered refresh is a *future supplement* only (it shows stale data for the
        first seconds after someone walks up, so the periodic loop stays the reliable core).
      (Note: this is about fetching **new data**. The separate *honesty* case — a closed-app
      widget holding a live-looking countdown past the staleness threshold — is already
      handled: #44 schedules a one-shot render-only redraw at the staleness boundary that flips
      it to `?` without any fetch, SPEC D4.)
      - **Render-only countdown tick while closed (Codex P1 on `ae0cf78`, deferred here).**
        Between redraws the widget's countdown text is static, so within the freshness window a
        closed-app countdown can read up to the staleness threshold optimistic ("2 min" for a
        train that has departed) before the one-shot redraw flips the whole widget to `?`.
        Making countdowns *advance/drop minute-by-minute* while closed needs periodic
        render redraws (~1/min per upcoming departure / label boundary) — the same periodic
        wake cadence this item defers (a battery decision, SPEC D5), just render-only rather
        than fetch+render. So it rides the "Live widget" loop above (which re-renders on its
        cadence anyway); the honesty floor (bounded optimism, then a stale flip) is the interim
        on the default path. Scheduling a redraw *per departure boundary* was considered and is
        the same cadence by another name (a chained wake every few minutes), so it isn't a
        cheaper middle ground — it's the deferred loop.
- [ ] **Carry disruption / line-status into the widget (own PR).** Maintainer: *yes, but a
      follow-up.* The widget's `across(...)` runs with empty `lineStatuses` and the snapshot
      has no disruptions, so a delayed/suspended service can show a normal-looking countdown.
      Requires the snapshot to persist an age-stamped status (a deliberate reversal of the
      `DeparturesSnapshot` KDoc, so `SPEC.md` records the reasoning). The per-row stale
      withhold (landed) already stops *old* numbers reading as live; this marks a *fresh*
      disrupted service on the widget.
- [ ] **Persist a refresh-failure kind / incompleteness for the widget (own PR, rides with the
      above).** Same schema reversal: the snapshot excludes the transient refresh-failure flag
      by design, so the widget can't say *why* data is old beyond the age stamp. Add a typed
      failure to the persisted schema so the widget can render offline/rate-limited/unreachable.
      **Includes the absent-stop case (Codex P1 on #44):** on an *initial* multi-stop refresh
      where one stop fails, `Snapshot.mergeStop` omits the failed stop entirely, so every
      persisted stop is `arrivalsFresh=true` and fresh — the widget's `uncertain` predicate
      can't tell a requested stop is missing and shows a clean "Updated just now". Persisting
      the expected stop set (or a `partialRefresh` flag) alongside the snapshot lets `uncertain`
      catch it. Deferred with the rest of this family (the snapshot deliberately carries only
      honest last-good + age); the per-row withhold + age stamp are the honesty floor until
      then, and it's moot once Phase 2's stable watched stops make the expected set known.
- [ ] **Deduplicate the widget's nearby set before the cap (own PR, Codex P2 on #44).** The
      in-app view calls `DepartureRows.nearbyDeduped(stopDistanceMeters)` so a line served by
      several adjacent stops collapses to its nearest stop; the widget renders from the
      persisted snapshot, which carries no distances, so it can't. Fix needs persisting the
      distances (or a widget-ready deduplicated selection) alongside the snapshot — a
      selection/schema decision, and moot once Phase 2's watched stops replace the interim
      nearby source. Until then adjacent stops can double up a line/direction in the six slots.
- [ ] **Scope the widget snapshot to its nearby set (own PR, Codex P1 on #44).** The widget
      snapshot is written only on an *authoritative* arrivals cycle, so if the user moves and
      the new set's fetch fails (offline/rate-limited), the previous location's departures stay
      on the widget — and because the widget shows no stop name, they read as live for the new
      context until the stamp ages them stale. An empty nearby resolution never mounts
      `DeparturesForStops` at all, so it can't clear either. Fix: scope/clear the persisted
      snapshot when the resolved nearby set changes (or resolves empty) and push a widget
      update. Same interim-nearby-source family as the dedupe bullet — the snapshot carries the
      stop *set*, so a fix is app-side (clear-if-different-set + `updateAll`), not a schema
      reversal, but it's throwaway surgery on the interim source and needs a device to verify
      the `updateAll`/blank-flicker behavior. Much *rarer* once Phase 2's stable watched stops
      replace the location-derived set (the set then changes only on an explicit edit, not on
      every location drift), but not eliminated — an edit whose first fetch fails still leaves
      the old set on the widget — so this scoping stays an open task even after Phase 2, until a
      render-time snapshot-vs-set comparison (or equivalent invalidation) lands. The aging stamp
      is the honesty floor meanwhile. **PR #53 attempted the app-side clear and was deferred
      (closed unmerged) — see *Decisions needing review*: seven race findings in three review
      rounds all traced to the same shape (an activity effect clearing the shared store
      concurrently with the per-set writers), which is a design signal, not seven bugs. Revisit
      as a render-path scoping (the redesign option below), not as more race patches.**
- [ ] **Size-aware row cap (own PR, Codex P2 on #44).** The fixed 6-row cap can clip at the
      110dp minimum height; derive the count from `LocalSize`. The node-assertion harness that
      landed can't verify "doesn't clip" (it asserts nodes, not pixels), so this waits on
      pixel rendering or a device check.
- [ ] **Named Overground pills on the widget (own PR, Codex P2 on #44).** The in-app pill
      renders the six named Overground lines as a *hollow* pill (surface fill + accent border
      + accent label, via `overgroundAccentColor`); the widget falls back to a neutral pill for
      them because `lineFillColor` returns null. Glance has no border modifier, so a hollow pill
      needs a nested-Box ring hack that the node harness can't verify — and a solid accent fill
      would reintroduce the tube-color collision #42's hollow treatment exists to avoid (Windrush
      red ≈ Central). So the widget shows a safe neutral pill for named Overground for now;
      revisit with a verifiable Glance hollow treatment (or once pixel rendering lands).

## Phase 5 — Distribution and polish

- [ ] Play internal-track deploy proven end to end; signing keystore via secrets. **The
      pipeline itself landed in Phase 0** (see the Deploy-job item there and
      `dev-docs/play-store-internal-track.md`); what remains is the human setup — upload keystore,
      Play Console app + seed upload, service account, the five `production`-environment
      secrets, and the Data Safety form — and one real push confirmed to reach the internal
      track.
- [ ] Consider a CI check that keeps `docs/play-store/icon-512.png` in step with the icon
      drawables. Measured on the siblings: **folding the assertion into an existing
      screenshot class is near-free; a dedicated Roborazzi step ≈ 8–9s/run** (the ~90s
      Robolectric cold start is paid once by the first screenshot step, so later steps run
      warm). Prefer folding over a dedicated step. Bigger CI-time lever, if it ever
      matters: batch the screenshot job's single-class steps the way simmo did (9→4 saved
      ~3min there) — the icon steps are not the cost. The 512 landed without a check for
      now, script-rendered via `scripts/render-store-icon.py`.
- [ ] **Fan out the release-notes-walk hardenings to the siblings** (Codex, PR #58): the
      "Build release notes" walk in `ci.yml`'s `deploy` job — copied verbatim from the
      sibling Android repos — carried several latent bugs that trackmo's copy now fixes
      and simmo / snoozemo / typelauncher / clothescast still have: (1) both the outer
      workflow-runs query and the per-run jobs query used `… || true`, masking an API
      failure as "no runs / not published" and risking a wrong range base (dropped or
      repeated notes); both now fail closed. (2) the walk skipped runs by head SHA, so a
      `workflow_dispatch` re-deploy of an already-published tip, and a re-run whose
      earlier attempt had published, both went undetected → older base picked, used
      versionCode re-uploaded. The walk now skips no runs and queries per-run jobs with
      `filter=all` (all attempts), relying on the "did the Play-upload step succeed" check
      + the supersession guard. Port to the four siblings' identical walks; maintainer
      coordinates the fan-out (one repo at a time; simmo is private/billed so it goes
      last). (3) Deferred (Codex, PR #58): in the rare case-2 fallback (nothing in the
      searched window has published yet — i.e. before the first-ever Play upload, or a long
      outage), the base is `${oldest_run_head}~1`, which includes only the oldest push's tip
      commit; if that push carried several commits (rebase-merge lands them together), the
      earlier ones are omitted and lost once this run becomes the next base. Correct fix
      needs the head of the run one older than the oldest seen, which the page cap may hide —
      entangled with the redesign below.
      Deeper option if this keeps producing edge cases: base the range on a durable
      marker (the last `v<versionCode>` prerelease that a Play upload accepted) instead of
      reconstructing it from the Actions API — a design change, its cost being that a
      GitHub prerelease is created even when the Play upload skips, so the marker must
      still encode "reached Play". Maintainer's call.
- [ ] **Peer-parity sweep** (requested 2026-09-19, on-device): confirm nothing peer-standard
      from the sibling apps is missing before release. The already-tracked peer features are
      the shareable debug-log export (its own item below), Settings (Phase 2), the
      licenses/About screen (above and Phase 0), and the Play internal track (above) — this
      bullet is only the check for anything else the peers have that fits here.
- [ ] Fail a **release** build when the git-derived versionCode/SHA fell back (a
      source-archive or no-git build): Play rejects a non-incrementing versionCode, so a
      silent fallback of `1` is wrong for a shipped artifact. The derivation logs a
      warning now; the hard release-side guard lands here with the deploy job that makes
      release integrity meaningful (Codex, PR #4).
- [ ] Licenses / About screen finalized.
- [ ] **Shareable bug-report export of the on-device log, with travel data redacted.**
      A user-shareable export of the diagnostic log so a bug report can carry it. The
      on-device logging exception does **not** extend to an artifact that leaves the
      device, so the export **redacts travel data** (stop IDs, line ids) — a shared file
      is subject to the same rule as any other artifact that leaves the machine
      (`AGENTS.md` *Privacy*). `docs/PRIVACY.md` already commits to this redaction; this is
      the item that implements it. **Cost £0** (a user-initiated share via the platform
      sheet, no service trackmo runs); the hand-off is a **Play Data Safety** consideration —
      a new off-device channel even after redaction — so the redaction is what keeps it a
      no-op for the declaration rather than a new data type collected, confirmed when built.
- [ ] Finalize the store-facing privacy disclosure (location, watched stops, the TfL
      requests) and the Play Data Safety answers — building on the debug-log disclosure
      that landed in Phase 1.

## Beyond MVP (not planned)

Directions that would change what trackmo *is*, not steps in the London MVP. Recorded so
they aren't re-derived; none is scheduled, and each needs the maintainer's go-ahead.

- [ ] (Later, open call) **Other cities beyond London** (recorded 2026-09-19 at the
      maintainer's request). Trackmo is TfL-specific today: the data layer talks only to
      the TfL Unified API, and line colors/codes are TfL's. The **domain layer**
      (`app.trackmo.domain` — stops, departures, staleness) is *shaped* around one
      departures model much of a multi-city version would reuse, but it is **not already
      provider-agnostic**: it carries TfL-specific contracts a second provider would have
      to **normalize or redesign, not just adapt behind an interface** — `TflClient` /
      `TflException`, `StopFinder`'s NaPTAN stop-type defaults, `LineStatus`'s TfL
      `statusSeverity` semantics, and `Departure`'s TfL direction/mode semantics. (Pill
      rendering is in the UI layer, `app.trackmo.ui.LinePill`, not the domain.) So the
      pathway is more than an adapter behind the data layer: the TfL contracts above are
      normalized, and a real-time provider is added. **GTFS** is the common denominator,
      which in practice is three feeds, not one — the static **Schedule** (the stop/route/
      trip catalog nearby-stop discovery and labels need), **Realtime trip updates** (live
      times keyed by the Schedule's IDs), and a **disruption source** (GTFS-Realtime
      *Service Alerts*, which are optional and sometimes a separate operator API): trackmo's
      honesty floor warns about a closed line or stop even with no predictions (SPEC
      principle 1; `lineStatuses`/`stopDisruptions`), so a provider lacking an alerts feed
      needs an honest fallback, never unverified-shown-as-clean. Plus per-city line styling
      and branding (the name reads as TfL-flavored). It's a scope expansion, not a refactor:
      **N data sources**, each with its own cost, rate limits, reliability, and **Play Data
      Safety** answer, and an app identity/branding question. Costs are per-provider and
      unknown until one is chosen — GTFS feeds are commonly free/open, but confirmed per
      city, not assumed. **This records the direction and the shape of the work, not an
      exhaustive feed or contract inventory** — the full scoping is done when the item is
      picked up. Product decision; not on the roadmap. **The full write-up —
      the feeds, the client-only-vs-backend-vs-aggregator fork, why London stays on the
      TfL Unified API, live-vs-scheduled and its UI treatment — is in
      `dev-docs/multi-city-gtfs.md`.**
- [ ] (Later, open call) **Smart-home integration** (recorded 2026-09-19 at the maintainer's
      request). Surface the next departures on a smart-home surface — a routine, a display, a
      voice assistant — so "when's my bus?" is answered without opening the phone. It means a
      new integration surface (Assistant/Home APIs or a local hub) with materially different
      cost and failure modes, and a **Play Data Safety** consequence (departures and possibly
      the watched set crossing to another system), and it changes what trackmo *is* beyond the
      London MVP. Direction only; needs the maintainer's go-ahead and its own scoping — which
      must record the chosen surface's **dollar cost** (hosted API vs. a local hub differ
      sharply; marked unknown until the surface is picked) and its degraded/offline behavior
      before implementation, per *Cost and reliability*.

## Decisions needing review

- **Widget-snapshot-scope (Codex P1 from #44) deferred: PR #53 closed unmerged; aging stamp
  is the honesty floor and the render-path scoping stays an open task (not closed by Phase 2)**
  (autopilot, maintainer said "defer 53"). The gap is
  real: after a move whose new-set fetch fails, the previous area's departures linger on the
  nameless widget reading as live until the stamp ages them stale (SPEC principle 1 / D4). PR
  #53's app-side clear worked, but Codex found **seven race findings across three review
  rounds**, every one the same shape — an activity-level effect clearing the shared snapshot
  store concurrently with the per-set `MainViewModel` writers (read-check-write; redraw-after-
  clear; retained writer saving after an Empty-path clear; cancellation mid-`updateAll`
  skipping the retry). The same shape recurring — this activity-effect clear racing the
  concurrent per-set writer lifecycle — is evidence about the design rather than seven separate
  bugs (the sibling repos' AGENTS.md codify that as a rule; trackmo's own does not, so this is
  the escalation's reasoning, not a trackmo policy citation). A design change is the
  maintainer's call — so this is escalated rather than patched an eighth time. The three
  options, cheapest-to-revisit first:
  - **Defer (chosen).** Close #53, keep the per-row withhold + aging stamp as the honesty
    floor. The widget still ages stale data to `?` on its own (the staleness redraw that *did*
    land), so the failure mode is "shows the old area's trains until the stamp ages them out",
    not "shows them as live forever". Cost: that window is ~5 minutes **plus** the staleness
    redraw's scheduling delay — it's a `WorkManager` `setInitialDelay` wake (deferrable, and
    Doze/batching can push it past the boundary), so on a closed-app widget the flip to stale
    isn't bounded to a hard 5 minutes. Fully reversible — the work is captured in the closed
    PR and item 691.
  - **Redesign race-free on the render path.** Move the scoping off the concurrent activity
    effect: persist the *current resolved set* on resolution, independently of the snapshot's
    own save path — a snapshot save happens only on a successful fetch, so binding the set to
    it would leave both pointing at the old area in exactly the failed-fetch case this exists
    to fix, preserving the wrong-location window rather than closing it (Codex's finding on the
    first cut of this note). The widget's own `provideGlance` then blanks a snapshot whose stop
    set doesn't match that independently-persisted current set, so the decision is made where
    the widget renders instead of by a second concurrent mutator. Deletes the whole race class
    rather than patching instances, but it needs a new persisted "current set" surface written
    on resolution (ownership coordinated with the snapshot writer) plus a device check — larger
    than #53 was.
  - **Keep patching #53's design.** Fix findings G (cancel the retained writer on the Empty
    path) and H (guarantee the redraw on cancellation) and ship. Rejected as autopilot's call:
    it's the eighth race patch on a shape that keeps producing them, exactly the move the
    design-signal reasoning above says not to make alone.
  Recommendation: option 2 if the window matters before Phase 2. Phase 2's stable watched stops
  make it much *rarer* — the set then changes only on an explicit edit, not on every location
  drift — but they do **not** eliminate it: an edit whose first arrivals fetch fails still
  leaves the previous set's departures on the widget, so the render-path scoping (or a
  render-time compare against an independently-persisted watched set) stays worthwhile even
  then, not fully mooted (Codex P1). **Maintainer's call.**
- **About/Licenses entry point is an overflow menu → About dialog → full-screen Licenses
  overlay, reachable from every state** (autopilot, licenses-screen PR). Trackmo has no nav
  graph and, until now, no About/Settings surface, so the licenses screen needed a home.
  Chosen: a `MoreVert` overflow in the departures top bar, **and** an "About" button on the
  location gate, both open the shared About dialog (app name + version); its one action opens
  the licenses list. The licenses route is hosted at the activity top level, above the
  gate/departures switch, as a `rememberSaveable`-gated overlay (system Back closes it via the
  screen's `BackHandler`). Hosting it above the gate (rather than inside the departures view)
  is what makes the legally-required attribution reachable when location is denied, and takes
  the departures refresh out of composition while it's open (both Codex P2s on the first
  cut). Alternatives weighed: a dedicated Settings screen (premature), or a nav library (a
  dependency for one destination). Reversible — a menu item, a gate button, a shared dialog,
  and a boolean swap in `MainActivity`. **Wants a maintainer look** at putting an About
  affordance on the permission-gate screen and at the overflow placement — a real Settings
  surface later would subsume both.
- **Widget staleness redraw uses `WorkManager`, one-shot at the boundary, armed from the render
  path** (autopilot, #44, maintainer said "no opinion" on implement-now vs defer). The
  app-closed honesty gap (Codex P1, raised twice) is closed by a single render-only `WorkManager`
  redraw at `snapshot.fetchedAt + THRESHOLD` that flips the widget to `?` (SPEC D4). It is armed
  from `provideGlance` (the render path), not from `save`: every path that shows the widget — add,
  host rebind, and the app's `updateAll` after a fetch (which re-runs `provideGlance`) — arms the
  flip from the snapshot it drew, which also means a host with no widget never schedules (no
  separate installed-id guard needed) and the widget-add-while-fresh case is covered. Codex raised
  three follow-on findings in this mechanism (no-widget churn, add-path gap) before it settled on
  the render path — that consolidation is the design fix that deleted the class.
  Alternatives weighed: **AlarmManager** (no new dependency, but no reboot persistence without
  a boot receiver, and inexact alarms are Doze-deferred just like WorkManager anyway); **defer
  the whole thing to D5** (rejected — it leaves the PR's own honesty claim with a hole Codex
  won't stop flagging). Cost: one new androidx dependency (`androidx.work:work-runtime-ktx`)
  and one deferrable, batched wake per snapshot — negligible battery, and not a polling
  cadence (fetching new data on a schedule stays deferred, see *Widget follow-ups*).
  Reversible — the scheduler is one file + one call site; swapping to AlarmManager or dropping
  it is contained. Wants a real-device check that the flip actually fires when the app is
  closed (and after a reboot).
- **Widget pixel test renders via `GlanceRemoteViews.compose`** (autopilot, #44). Rather than
  water down the AGENTS.md "Glance layouts get Roborazzi screenshots" rule to node-only, the
  widget is genuinely pixel-captured by composing it to RemoteViews and inflating them to a
  `View` (`WidgetScreenshotTest`). The API is `@ExperimentalGlanceRemoteViewsApi` — if a glance
  bump changes it, this test's render path may need adjusting (the node-based `WidgetContentTest`
  is unaffected). Reversible — it's one test file + one CI step. Baselines are committed but CI
  records (doesn't verify) them; the drift-refresh/verify gate is wired in the
  screenshot-drift-refresh PR (pending the post-merge `repo setup` token step).
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
  adding the gate is additive. **Superseded by the screenshot-drift-refresh PR**, which
  wires the apparatus mirroring the siblings: `screenshot-tests` clears-then-records,
  enforces the `--tests` allow-list, and fails on drift on pushes/forks;
  `sync-screenshots` (`mikelward/ci-commit-artifact@main`) pushes the refreshed set back to
  same-repo PR branches; `post-screenshot-diff` posts the before/after comment. Kept OUT of
  the required `lanes` gate until `repo setup` provisions `CI_COMMIT_ARTIFACT_TOKEN`
  post-merge, so its bootstrap failure doesn't block that PR.
- **Brand accent = red, and Material You (dynamic color) off by default** (maintainer
  "let's try red", 2026-09-19). `TrackmoTheme` now seeds a red `primary` (with its
  container/secondary/tertiary partners) so red reads as an accent on buttons and the
  refresh/progress indicators over neutral surfaces — deliberately *not* the app-bar
  container, to keep it an accent not a wash. Dynamic color is off so the wallpaper can't
  override the brand (leaving it on was why the app read as a neutral charcoal on-device).
  This is a **first pass at the color** the maintainer asked to try, not a settled brand:
  the exact red (`0xFFB3261E` light) and whether to accent the app bar are both open, and
  reversible — the scheme is two colour tables plus one default flag in `Theme.kt`, and
  flipping `dynamicColor` back on restores Material You. Promote to `SPEC.md` once the
  colour is confirmed on a device.
- **Live widget refresh uses mechanism B (WorkManager one-shot chain), A recorded as the
  follow-up** (maintainer: *start with B, record A as a possible follow-up if B doesn't
  work*, 2026-09-20). The opt-in "refresh widget every minute" setting drives a
  self-rescheduling `OneTimeWorkRequest` chain (`WidgetRefreshWorker`) rather than a
  foreground service. B is lighter (no persistent notification, no Play
  foreground-service-type declaration, less battery) and adequate for the scoped
  screen-on case, but it is **Doze-deferred**, so it does not guarantee the exact minute
  with the screen off. Mechanism A (a foreground service) would, at those costs, and is
  recorded under *Widget follow-ups* to take only if the screen-on case proves
  insufficient on a real device. Reversible — B is contained to `WidgetRefreshWorker` +
  the settings store + one call site; swapping to A is additive. Wants a real-device
  check that the ~1/min chain actually holds while the screen is on (and that Doze
  behaves as expected when it isn't).
- **Settings screen is a top-level overlay reached from the departures overflow only**
  (autopilot, this PR — maintainer asked for a Settings screen and said "the Settings
  screen should come first" then "sequence how you like"). Hosted at the activity top
  level like the licenses overlay (a `BackHandler`-closed screen, no nav library), added
  as a "Settings" item in the departures overflow menu **above** "About". The location
  gate's menu still offers About alone — its existing "Open settings" affordance is the OS
  app-settings for permissions, a different thing, so putting our Settings there would be
  confusing while location is denied. The screen composable is UI-only (reflects the
  setting, reports a change); persistence + the WorkManager scheduler are wired by the
  activity, keeping it Robolectric-renderable. Reversible — a menu item, a boolean overlay
  swap, and a UI-only composable. Wants a maintainer look at whether Settings also belongs
  on the gate, and at the overflow ordering.

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
