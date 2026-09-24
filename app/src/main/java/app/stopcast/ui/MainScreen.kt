@file:OptIn(ExperimentalMaterial3Api::class)

package app.stopcast.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import app.stopcast.domain.AlertLinks
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopcast.R
import app.stopcast.domain.cleanDisruptionBody
import app.stopcast.domain.Connections
import app.stopcast.domain.Countdown
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DestinationAbbreviations
import app.stopcast.domain.DismissedAlert
import app.stopcast.domain.HiddenModes
import app.stopcast.domain.NoTimes
import app.stopcast.domain.RelativeTime
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.JourneyCall
import app.stopcast.domain.JourneyChange
import app.stopcast.domain.JourneyEnd
import app.stopcast.domain.WidgetJourneyCheck
import app.stopcast.domain.LineSequence
import app.stopcast.domain.StarredJourney
import app.stopcast.domain.Journeys
import app.stopcast.domain.StopDistance
import app.stopcast.domain.StopGroup
import app.stopcast.domain.StopGrouping
import app.stopcast.domain.StopLocation
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.JourneySegment
import app.stopcast.domain.JourneyTrains
import app.stopcast.domain.WidgetJourneys
import app.stopcast.domain.TflException
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableStateMapOf
import app.stopcast.domain.stopPlaceKey
import app.stopcast.domain.StopQualifier
import app.stopcast.domain.abbreviateBranch
import app.stopcast.domain.RouteFocus
import app.stopcast.domain.followedDeparture
import app.stopcast.domain.PlatformDirection
import app.stopcast.domain.routeDepartures
import app.stopcast.ui.theme.LocalStarredBorderColor
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/** Test tag on the red "update available" dot overlaying the overflow menu icon. */
internal const val UPDATE_AVAILABLE_DOT_TAG = "update_available_dot"

/**
 * The departures view (SPEC D8): a flat list, one row per (service, stop, direction),
 * soonest-first across the watched stops. Pure — it renders only [state] and the
 * caller-supplied [now], with no I/O in composition (SPEC jank-free UI), so the same
 * function drives the app and the screenshot tests.
 *
 * The rows are recomputed from the snapshot's raw stops against [now], not cached from
 * fetch time, so a departed service leaves the list and the ordering advances as the
 * clock ticks (SPEC D4). Once the snapshot is [Staleness]-stale the countdowns are
 * withheld — the prediction set is likely wrong, so the honest answer is "refresh"
 * rather than live-looking numbers.
 */
@Composable
fun MainScreen(
    state: DeparturesUiState,
    now: Instant,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // The full list's scroll position. Hoisted by the caller above the route page and the overlays,
    // which take this screen (or its list) out of composition, so a return lands where it was.
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    // From "near me now" (`stopId` → meters): collapse a line served by several adjacent
    // nearby stops to its nearest stop. Empty for a location-free list, shown unchanged.
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    // Shows a near-me stop (`stopId`, place name) in the maps app, from a tap on its header's
    // distance; null leaves the distance inert.
    onOpenStopMap: ((String, String) -> Unit)? = null,
    // The starred journeys (SPEC *Journeys*), each already turned so its origin is the end nearer the
    // rider: shown as cards atop the near-me list, and starrable from a route page's stop list.
    journeys: List<StarredJourney> = emptyList(),
    // The journeys more than a mile from the rider (key → meters to the nearer end): held behind the
    // Faraway favorites button, and not fetched until it's tapped ([Journeys.farJourneys]).
    farJourneyMeters: Map<String, Double> = emptyMap(),
    // The nearby stop set shown ([NearbyStopsViewModel.State.Ready.clusterSetKey]): a tap on Faraway
    // favorites holds for this set only, so a relocation elsewhere holds far journeys back again.
    nearbyKey: String? = null,
    // The Faraway favorites tap; the app hoists it above the overlays so opening one keeps it.
    farReveal: FarRevealState = rememberFarReveal(nearbyKey),
    onToggleJourney: ((StarredJourney) -> Unit)? = null,
    // Dismisses the route page's tip on starring a journey; null (dismissed, or not read yet) hides it.
    onDismissJourneyTip: (() -> Unit)? = null,
    // The journeys' far ends as shown, reported so their closures are checked; and those checks, as
    // departure-less stops carrying any stop-level disruption (SPEC *Journeys*).
    onJourneyDestinations: (List<StopRef>) -> Unit = {},
    journeyDestinationStops: List<StopArrivals> = emptyList(),
    // The far ends whose closure check failed with nothing known ([JourneyCard.destinationUnchecked]).
    journeyDestinationsUnknown: Set<String> = emptySet(),
    // Shows the other direction of a journey card (a tap on its header).
    onFlipJourney: (StarredJourney) -> Unit = {},
    // True while a journey-star write has failed and not yet been surfaced: the same acknowledged
    // snackbar seam as [starWriteFailed], cleared by [onJourneyWriteFailureShown].
    journeyWriteFailed: Boolean = false,
    onJourneyWriteFailureShown: () -> Unit = {},
    // The stops to fetch for the journey cards (each journey's origin this way round), reported
    // whenever they change; the ViewModel fetches them alongside the near-me stops.
    onJourneyOrigins: (List<StopRef>) -> Unit = {},
    // The same stops by journey (key → its origin and neighboring poles' ids), and the journey whose
    // own view is open (kept however far), so a relocate can drop a journey's stops the moment it's
    // held back, before the screen reports again.
    onJourneyStopIds: (Map<String, Set<String>>, String?) -> Unit = { _, _ -> },
    // The starred journeys' keys and each placed journey's latest check (the departures found to call
    // at its far end), for the widget to pin (it can't load route data itself); reported whenever
    // they change. The ViewModel keeps what they add up to.
    onWidgetJourneys: (Set<String>, List<WidgetJourneyCheck>, Map<String, String>, Map<String, Set<String>>) -> Unit =
        { _, _, _, _ -> },
    // Whether [journeys] is the saved list yet: until it is, nothing is reported for the widget, so
    // a list still loading isn't taken for "no journeys" and unpins them.
    journeysKnown: Boolean = true,
    // True only until the saved journeys' first read arrives (not when that read failed): an open
    // journey view restored across a rotation waits this out rather than closing.
    journeysLoading: Boolean = false,
    // The rows the user has starred (SPEC D8): pinned to the top, and their star filled.
    // Empty by default so an unwired build/test renders the plain soonest-first list.
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    // False only when the stored star set is a newer-schema file this build can't read: the
    // star control is then hidden rather than shown unfilled (which would falsely read as
    // "nothing starred"). Defaults true, the normal case.
    starringAvailable: Boolean = true,
    // True while a star write has failed and not yet been surfaced (SPEC principle 2): the
    // screen shows a transient snackbar so a tap that didn't take isn't swallowed silently,
    // then calls [onStarWriteFailureShown] to clear it. Acknowledged state, not a one-shot
    // event, so a rotation between the failed tap and the snackbar doesn't drop it. False by
    // default so an unwired build/test renders no snackbar.
    starWriteFailed: Boolean = false,
    onStarWriteFailureShown: () -> Unit = {},
    // The service alerts the user has dismissed (SPEC *Disruptions*): matching closure cards and line
    // statuses are hidden until their content changes. Empty by default so an unwired build/test
    // shows every alert. [onDismissAlert] is called with the alert's row when its dismiss is tapped.
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // True while a dismiss write has failed and not yet been surfaced (SPEC principle 2): same
    // snackbar seam as [starWriteFailed], so a dismiss tap that didn't persist isn't swallowed
    // silently. Acknowledged state; the screen calls [onDismissWriteFailureShown] to clear it.
    dismissWriteFailed: Boolean = false,
    onDismissWriteFailureShown: () -> Unit = {},
    // Open the open-source licenses screen (from the About dialog). Default no-op so an
    // unwired build/test renders the screen without a licenses destination.
    onOpenLicenses: () -> Unit = {},
    // Open the Settings screen (from the overflow menu). Default no-op so an unwired build/test
    // renders the screen without a settings destination.
    onOpenSettings: () -> Unit = {},
    // True when Google Play reports a newer version is available: the overflow icon gets a red
    // dot and the menu gains an "Update available" item. Off by default (and in debug — see
    // PlayUpdateChecker), so the common build/test renders the plain overflow.
    updateAvailable: Boolean = false,
    // Open the Play Store listing (from the "Update available" item). Default no-op.
    onOpenAppListing: () -> Unit = {},
    // The modes (or the generic bucket, an empty string) that still have a farther "More" cluster
    // to page in (SPEC *Finding stops → Near me now*). A "More …" button per mode is shown at the
    // foot of the near-me list. Empty by default so a location-free or fully-revealed list shows
    // none. [onReveal] is called with the tapped mode.
    revealableModes: Set<String> = emptySet(),
    onReveal: (String) -> Unit = {},
    // Start the consent-gated bug report (from the overflow). Default no-op so an unwired
    // build/test renders the menu without one.
    onSendBugReport: () -> Unit = {},
    // Non-null when the shown stops are backed by a low-confidence location (SPEC *Finding stops*):
    // a top banner over the list says so and offers "Try again" (which runs [onRefresh], a re-locate).
    // Null hides it. Default null so an unwired build/test renders without it.
    locationBanner: LocationBanner? = null,
    // The transport modes hidden from the near-me list (SPEC *Finding stops → Hiding a mode*): their
    // rows are left out and a one-line banner says so, with [onShowAllModes] to show them again. A
    // non-null [onHideMode] makes a long press on a near-me row or header offer "Hide ‹mode›".
    hiddenModes: Set<String> = emptySet(),
    onHideMode: ((String) -> Unit)? = null,
    onShowAllModes: () -> Unit = {},
    // A change of hidden modes failed to save: a snackbar says so, then [onHiddenModesWriteFailureShown].
    hiddenModesWriteFailed: Boolean = false,
    onHiddenModesWriteFailureShown: () -> Unit = {},
    // Open the station search (SPEC *Finding stops*); null hides the overflow's "Find a station" item.
    onFindStation: (() -> Unit)? = null,
    // Non-null when this screen shows one searched station rather than the near-me list (SPEC
    // *Finding stops*): the app bar is titled by it with a back arrow ([onCloseStation]) in place of
    // the app's mark, and the overflow menu is left out — it belongs to the main list.
    stationTitle: String? = null,
    onCloseStation: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Overflow-menu and About-dialog visibility. Saved so an open dialog survives rotation.
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val starWriteFailedMessage = stringResource(R.string.star_write_failed)

    // The rows the screen renders, grouped against the live clock (SPEC D4) — computed once here so
    // both the list and the route-detail page below read the SAME rows. Empty for any non-Loaded
    // state. Cheap and pure; line statuses stamp each row so a disrupted line is marked (SPEC D3).
    val loaded = state as? DeparturesUiState.Loaded
    // The routes (both directions) of each journey's starred line and of every line at its origin,
    // which say where the journey boards and alights and which trains or buses call at the far end.
    // From the process cache at once when a line was loaded already; otherwise fetched off the render
    // path (the same lookup the route page makes, one or two requests per line a day). A failed
    // load is kept as such (null), so the card says so and offers a retry rather than checking forever.
    val routeStopsRepository = LocalRouteStops.current
    var journeyRouteRetry by rememberSaveable { mutableIntStateOf(0) }
    val loadedSequences = remember { mutableStateMapOf<String, LineSequence?>() }
    // The loaders below run again each hour, so a route or area the repository has let expire (a day
    // old) is refetched while the screen stays up; the old copy shows until the new one is in.
    val routeRecheck = now.epochSecond / 3600
    fun sequencesFor(lineIds: Collection<String>): Map<String, LineSequence?> = buildMap {
        for (id in lineIds) {
            if (id in loadedSequences) put(id, loadedSequences[id]) else routeStopsRepository?.cached(id, "")?.let { put(id, it) }
        }
    }
    // The starred journey whose own view is open (its key), from a tap on its heading, or null.
    var journeyViewKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the rider has tapped "Faraway favorites" to show the far journeys in full, for this
    // nearby set ([rememberFarReveal]).
    val farRevealed = farReveal.revealed
    // The journeys shown in full and fetched: the near ones, the far ones once revealed, and a far
    // one while its own view is open.
    val cardJourneys = remember(journeys, farJourneyMeters, journeyViewKey, farRevealed) {
        journeys.filter { it.key !in farJourneyMeters || farRevealed || it.key == journeyViewKey }
    }
    val journeyStarLines = remember(cardJourneys) { cardJourneys.map { it.lineId }.filter { it.isNotBlank() }.distinct() }
    val starSequences = sequencesFor(journeyStarLines)
    // Where each journey boards and alights this way round: a bus's way back uses other poles.
    val journeySegments = remember(cardJourneys, starSequences) {
        cardJourneys.associate { j -> j.key to starSequences[j.lineId]?.let { Journeys.segment(j, it) } }
    }
    // A bus journey's origin stop area (from its starred line's route) and the area's poles, looked
    // up once a day off the render path: another line may board beside the origin (stop K by
    // stop L) and reach the far end too (SPEC *Journeys*). A failed lookup is kept as such (null).
    val journeyAreas = remember(cardJourneys, journeySegments, starSequences) {
        cardJourneys.filter { it.bus }.mapNotNull { j ->
            val originId = journeySegments[j.key]?.originId ?: return@mapNotNull null
            starSequences[j.lineId]?.stopAreas?.get(originId)?.takeIf { it.isNotBlank() }?.let { j.key to it }
        }.toMap()
    }
    val loadedPoles = remember { mutableStateMapOf<String, List<StopLocation>?>() }
    LaunchedEffect(routeStopsRepository, journeyAreas, journeyRouteRetry, routeRecheck) {
        val repository = routeStopsRepository ?: return@LaunchedEffect
        for (areaId in journeyAreas.values.distinct()) {
            val held = loadedPoles[areaId]
            if (held != null && repository.cachedPoles(areaId) != null) continue
            if (held == null) loadedPoles.remove(areaId)
            val poles = repository.cachedPoles(areaId) ?: try {
                repository.loadPoles(areaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Logged (sanitized) by the repository; null marks the failure for the card, unless
                // an expired copy is held: route data a day old beats none.
                held
            }
            loadedPoles[areaId] = poles
        }
    }
    // Each bus journey's area poles, by journey key: absent while loading, null when it failed.
    val journeyPoles: Map<String, List<StopLocation>?> = journeyAreas.mapNotNull { (key, areaId) ->
        if (areaId in loadedPoles) key to loadedPoles[areaId] else null
    }.toMap()
    // The stop each journey is fetched from: its resolved origin, or, before its route is in, a
    // station's own id (the same both ways) — a bus waits, since its way-back pole isn't known yet.
    val journeyOrigins = remember(cardJourneys, journeySegments, starSequences, journeyPoles) {
        cardJourneys.mapNotNull { j ->
            val id = journeySegments[j.key]?.originId ?: j.from.stopId.takeUnless { j.bus } ?: return@mapNotNull null
            // The starred line, plus every line of its mode the route data lists at the origin (the
            // 134 beside the 43), so one with no predictions still has its route loaded and its
            // status checked — a suspended route shows its warning, not "no buses".
            // Only lines of a known, matching mode: an interchange's lines come in with a blank mode
            // when it mixes modes (King's Cross's buses beside its tube), and loading every one
            // would spend the request budget on routes that don't serve this stop.
            // A journey saved with no mode (TfL left it off) takes its line's known one.
            val mode = j.mode.ifBlank { Connections.knownMode(j.lineId).orEmpty() }
            val served = starSequences[j.lineId]?.stopLines?.get(id).orEmpty()
                .filter { it.mode.isNotBlank() && it.mode.equals(mode, ignoreCase = true) }
            // Its pole letter and area, once looked up, so the card heads it like its neighbors.
            val pole = journeyPoles[j.key]?.firstOrNull { it.id == id }
            StopRef(
                id, j.from.name, lines = listOf(j.line) + served,
                clusterId = pole?.clusterId.orEmpty(), stopLetter = pole?.stopLetter.orEmpty(),
                bearing = pole?.bearing.orEmpty(), towards = pole?.towards.orEmpty(),
                // Its interchange, so a closure there folds and titles by the interchange (SPEC
                // *Disruptions*) when no nearby stop brought it in.
                hubId = Journeys.originHub(id, starSequences[j.lineId], pole),
            )
        }.let(::mergeJourneyOrigins)
    }
    val originLines = remember(loaded?.stops, journeyOrigins) {
        val ids = journeyOrigins.mapTo(HashSet()) { it.id }
        loaded?.stops.orEmpty().filter { it.stopId in ids }
            .flatMap { stop -> stop.departures.map { it.lineId } + stop.lines.map { it.id } }
    }
    // The lines boarding beside a bus journey's origin (and not at it), so their routes can say
    // whether they reach the far end ([Journeys.siblingPoles]).
    val siblingLines = remember(cardJourneys, journeySegments, journeyPoles) {
        cardJourneys.flatMap { j ->
            val originId = journeySegments[j.key]?.originId ?: return@flatMap emptyList()
            val poles = journeyPoles[j.key].orEmpty()
            val atOrigin = poles.firstOrNull { it.id == originId }?.lines.orEmpty().mapTo(HashSet()) { it.id }
            poles.filter { it.id != originId }.flatMap { pole ->
                pole.lines.filter { it.id !in atOrigin && Journeys.ofMode(it, j.mode) }.map { it.id }
            }
        }
    }
    val journeyLineIds = remember(journeyStarLines, originLines, siblingLines) {
        (journeyStarLines + originLines + siblingLines).filter { it.isNotBlank() }.distinct()
    }
    LaunchedEffect(routeStopsRepository, journeyLineIds, journeyRouteRetry, routeRecheck) {
        val repository = routeStopsRepository ?: return@LaunchedEffect
        for (lineId in journeyLineIds) {
            val held = loadedSequences[lineId]
            if (held != null && repository.cached(lineId, "") != null) continue
            if (held == null) loadedSequences.remove(lineId)
            loadedSequences[lineId] = try {
                repository.load(lineId, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Logged (sanitized) by the repository; null marks the failure for the card, unless
                // an expired copy is held: route data a day old beats none.
                held
            }
        }
    }
    val journeySequences = sequencesFor(journeyLineIds)
    // The poles beside each bus journey's origin that board a line reaching its far end, fetched
    // alongside the origin (one arrivals request each) and shown on its card under their letter.
    val journeySiblings = remember(cardJourneys, journeySegments, journeyPoles, journeySequences) {
        cardJourneys.mapNotNull { j ->
            val originId = journeySegments[j.key]?.originId ?: return@mapNotNull null
            val poles = journeyPoles[j.key] ?: return@mapNotNull null
            j.key to Journeys.siblingPoles(j, originId, poles, journeySequences)
        }.toMap()
    }
    val siblingOrigins = remember(cardJourneys, journeySiblings) {
        cardJourneys.flatMap { j ->
            journeySiblings[j.key]?.poles.orEmpty().map { pole ->
                pole.toStopRef().copy(lines = pole.lines.filter { Journeys.ofMode(it, j.mode) })
            }
        }.distinctBy { it.id }.filter { sibling -> journeyOrigins.none { it.id == sibling.id } }
    }
    // Each journey's far end this way round: its own stop, and the stops the route places it at (a
    // bus's other poles), whose closure the card shows.
    val journeyDestinationIds = remember(cardJourneys, journeySegments) {
        cardJourneys.associate { j -> j.key to (setOf(j.to.stopId) + journeySegments[j.key]?.destinationIds.orEmpty()) }
    }
    val reportJourneyOrigins by rememberUpdatedState(onJourneyOrigins)
    LaunchedEffect(journeyOrigins, siblingOrigins) { reportJourneyOrigins(journeyOrigins + siblingOrigins) }
    val reportJourneyStopIds by rememberUpdatedState(onJourneyStopIds)
    val journeyStopIds = remember(cardJourneys, journeySegments, journeySiblings) {
        cardJourneys.associate { j ->
            val originId = journeySegments[j.key]?.originId ?: j.from.stopId
            j.key to (setOf(originId) + journeySiblings[j.key]?.poles.orEmpty().map { it.id })
        }
    }
    LaunchedEffect(journeyStopIds, journeyViewKey) { reportJourneyStopIds(journeyStopIds, journeyViewKey) }
    // The journey cards: the trains or buses from each journey's origin that call at its far end, on
    // any line, the origin's closure notice if it has one, or why they can't be shown yet (SPEC
    // principle 1).
    val journeyCards = remember(
        loaded?.stops, loaded?.lineStatuses, loaded?.unavailableStopIds, now, cardJourneys, journeySegments,
        journeySequences, dismissed, journeyAreas, journeyPoles, journeySiblings, journeyDestinationStops,
        journeyDestinationIds, journeyDestinationsUnknown,
    ) {
        val ld = loaded
        // Dismissals apply here as on the list, so an alert dismissed anywhere is gone from the card.
        val across = DepartureRows.withoutDismissed(
            ld?.let { DepartureRows.across(it.stops, now, it.lineStatuses) }.orEmpty(),
            dismissed,
        )
        cardJourneys.map { journey ->
            val segment = journeySegments[journey.key]
            val originId = segment?.originId ?: journey.from.stopId
            val origin = ld?.stops?.firstOrNull { it.stopId == originId }
            // Every boarding stop's closure notice: the origin's, and each neighboring pole's.
            val boardingStops = listOf(originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
            // The far-end stops the card's departures reach (another line may use another pole).
            var reached = emptySet<String>()
            // The stop being fetched: known before the route is in for a station (see journeyOrigins).
            val fetchedId = segment?.originId ?: journey.from.stopId.takeUnless { journey.bus }
            val state = when {
                // Asked for and not come back: the fetch failed with nothing earlier to show.
                origin == null && fetchedId != null && fetchedId in ld?.unavailableStopIds.orEmpty() ->
                    JourneyCardState.NotChecked()
                journey.lineId !in journeySequences -> JourneyCardState.Checking
                journeySequences[journey.lineId] == null -> JourneyCardState.RouteFailed
                // The route can't place the stops this way round (no single way-back stop).
                segment == null -> JourneyCardState.NotChecked()
                origin == null -> JourneyCardState.Checking
                // A bus origin's neighboring poles still being looked up, or their lines' routes loading.
                journey.key in journeyAreas && journey.key !in journeyPoles -> JourneyCardState.Checking
                journeySiblings[journey.key]?.pendingLines.orEmpty().isNotEmpty() -> JourneyCardState.Checking
                else -> {
                    val siblings = journeySiblings[journey.key]?.poles.orEmpty()
                    val siblingStops = siblings.map { pole -> ld?.stops?.firstOrNull { it.stopId == pole.id } }
                    // The origin's trains, then each neighboring pole's (matched to the far end the same way).
                    val parts = listOf(Journeys.trains(segment, across, journeySequences, journey)) +
                        siblings.map { pole -> Journeys.trains(JourneySegment(pole.id, emptySet()), across, journeySequences, journey) }
                    val trains = JourneyTrains(
                        rows = parts.flatMap { it.rows },
                        pending = parts.any { it.pending },
                        unresolved = parts.any { it.unresolved },
                        routeFailed = parts.any { it.routeFailed },
                    )
                    // Trains on another branch, offered with where to change when no direct one is due.
                    val changes = Journeys.changesWithoutDirect(trains.rows, parts.flatMap { it.changes })
                    reached = parts.flatMapTo(HashSet()) { it.reachedIds }
                    // A neighboring pole whose fetch failed, whose lookup did, or whose line's route
                    // did, may have had a bus.
                    val polesFailed = journey.key in journeyPoles && journeyPoles[journey.key] == null ||
                        journeySiblings[journey.key]?.failedLines.orEmpty().isNotEmpty()
                    val siblingsMissed = siblings.zip(siblingStops).any { (pole, stop) ->
                        stop == null && pole.id in ld?.unavailableStopIds.orEmpty()
                    } || polesFailed
                    // "No trains" is only a claim a fresh, current fetch can make: an origin whose last
                    // refresh failed (kept aged) or has gone stale says it couldn't check instead —
                    // and so does one with a departure whose path couldn't be resolved, since it may
                    // well call at the far end. A line whose route is still loading says checking.
                    // The same holds for each neighboring pole.
                    val current = (listOf(origin) + siblingStops.filterNotNull()).all { stop ->
                        stop.arrivalsFresh && !Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())
                    }
                    // A line still loading holds the whole card at "checking", so a first line's trains
                    // aren't shown as if they were all; one that couldn't be checked is said so beneath
                    // the rest.
                    when {
                        trains.pending -> JourneyCardState.Checking
                        // A neighboring pole asked for and not in yet.
                        siblings.zip(siblingStops).any { (pole, stop) -> stop == null && pole.id !in ld?.unavailableStopIds.orEmpty() } ->
                            JourneyCardState.Checking
                        trains.rows.isNotEmpty() || changes.isNotEmpty() ->
                            JourneyCardState.Trains(
                                trains.rows,
                                // With only trains to change from, "no direct trains" is a claim
                                // only a fresh, current fetch can make too.
                                incomplete = trains.unresolved || siblingsMissed || trains.rows.isEmpty() && !current,
                                retry = trains.routeFailed || polesFailed,
                                changes = changes,
                            )
                        // A route that failed to load is the one gap a retry can close.
                        trains.routeFailed -> JourneyCardState.RouteFailed
                        !current || trains.unresolved || siblingsMissed -> JourneyCardState.NotChecked(retry = polesFailed)
                        else -> JourneyCardState.Trains(emptyList())
                    }
                }
            }
            // A complete check judged every departure at the origin: the widget drops any it now rejects.
            // Per boarding stop: the origin, then each neighboring pole (pinned separately on the widget).
            val boardingIds = listOfNotNull(segment?.originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
            val checked =
                if (state is JourneyCardState.Trains && !state.incomplete) {
                    boardingIds.associateWith { id ->
                        across.filter { it.stopId == id && it.lineId.isNotBlank() }
                            .flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) }
                    }
                } else {
                    emptyMap()
                }
            // And the far end's (closed or moved), from its own check, for every stop the card's departures
            // reach there: a journey can't end as shown. One card per notice, however many poles carry it.
            val destinationIds = journeyDestinationIds[journey.key].orEmpty() + reached
            val destinationClosures = DepartureRows.withoutDismissed(
                DepartureRows.across(journeyDestinationStops.filter { it.stopId in destinationIds }, now),
                dismissed,
            ).filter { it.stopDisruption != null }.groupBy { it.stopDisruption }.values.toList()
            val closures = boardingStops.mapNotNull { id ->
                across.firstOrNull { it.stopId == id && it.stopDisruption != null }?.let(::listOf)
            } + destinationClosures
            // A destination whose check failed with nothing known: the card says so, not "open".
            val destinationUnchecked = destinationIds.any { it in journeyDestinationsUnknown }
            JourneyCard(journey, state, closures, checked, boardingIds, destinationIds, destinationUnchecked)
        }
    }
    // The far ends to check for a closure.
    val journeyDestinations = remember(journeyCards) {
        journeyCards.flatMap { card -> card.destinationIds.map { id -> StopRef(id, card.journey.to.name) } }.distinctBy { it.id }
    }
    val reportJourneyDestinations by rememberUpdatedState(onJourneyDestinations)
    LaunchedEffect(journeyDestinations) { reportJourneyDestinations(journeyDestinations) }
    // What each placed journey's card found for the widget (it can't load routes itself): the
    // origin's departures that call at the far end, by line, destination and branch, and — from a
    // complete check — every departure it judged. The ViewModel merges these into what it pins.
    // The widget follows the same rule as the list: only near journeys are pinned there, so a far
    // one opened in the app doesn't join it.
    val journeyKeys = remember(cardJourneys, farJourneyMeters) {
        cardJourneys.filter { it.key !in farJourneyMeters }.mapTo(HashSet()) { it.key }
    }
    // The direction each journey is shown in, so a flip reaches the widget even before its route
    // can place the new origin.
    val journeyShownFrom = remember(journeyKeys, cardJourneys) {
        cardJourneys.filter { it.key in journeyKeys }.associate { it.key to it.from.stopId }
    }
    // One check per boarding stop: the origin under the journey's key, a neighboring pole under its
    // [WidgetJourneys.poleKey], each pinned on the widget from its own stop.
    val widgetJourneyChecks = remember(journeyCards, journeyKeys) {
        journeyCards.filter { it.journey.key in journeyKeys }.flatMap { card ->
            val key = card.journey.key
            val rows = (card.state as? JourneyCardState.Trains)?.rows.orEmpty()
            card.boardingIds.mapIndexed { i, id ->
                WidgetJourneyCheck(
                    if (i == 0) key else WidgetJourneys.poleKey(key, id),
                    id,
                    rows.filter { it.stopId == id }.flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) },
                    card.checked[id].orEmpty(),
                    card.journey.from.stopId,
                )
            }
        }
    }
    // The boarding keys of each journey whose neighboring poles are settled (none to look up, or
    // looked up and judged), so a pole that no longer qualifies loses its widget pin.
    val widgetJourneyBoarding = remember(journeyCards, journeyAreas, journeyPoles, journeySiblings) {
        journeyCards.filter { card ->
            val key = card.journey.key
            card.boardingIds.isNotEmpty() &&
                (key !in journeyAreas || journeyPoles[key] != null && journeySiblings[key]?.settled == true)
        }.associate { card ->
            val key = card.journey.key
            key to card.boardingIds.mapIndexedTo(HashSet()) { i, id -> if (i == 0) key else WidgetJourneys.poleKey(key, id) }
        }
    }
    val reportWidgetJourneys by rememberUpdatedState(onWidgetJourneys)
    LaunchedEffect(journeysKnown, journeyKeys, widgetJourneyChecks, journeyShownFrom, widgetJourneyBoarding) {
        if (journeysKnown) reportWidgetJourneys(journeyKeys, widgetJourneyChecks, journeyShownFrom, widgetJourneyBoarding)
    }
    // What the journey cards above already show: a near-me row they cover in full isn't repeated.
    val journeyRowsShown = remember(journeyCards) {
        journeyCards.flatMap { (it.state as? JourneyCardState.Trains)?.shownRows.orEmpty() }
    }
    // Each place's modes, less those already hidden, for a header's "Hide ‹mode›" items.
    val placeModesShown = remember(loaded?.stops, hiddenModes) {
        placeModes(loaded?.stops.orEmpty()).mapValues { (_, modes) ->
            modes.filterNotTo(LinkedHashSet()) { HiddenModes.isHidden(it, hiddenModes) }
        }
    }
    val nearbyRows = remember(loaded?.stops, loaded?.lineStatuses, now, stopDistanceMeters, dismissed, hiddenModes) {
        val ld = loaded ?: return@remember emptyList()
        // A near-me list shows its nearby stops only: a journey's farther origin, fetched for its
        // card above, isn't one of them (SPEC *Journeys*).
        val shownStops = if (stopDistanceMeters.isEmpty()) ld.stops else ld.stops.filter { it.stopId in stopDistanceMeters }
        val across = HiddenModes.rows(DepartureRows.across(shownStops, now, ld.lineStatuses), hiddenModes)
        // A "near me now" list (distances present) shows a line once, from its nearest stop, then
        // orders closest-stop-first (soonest breaks a same-stop tie). A location-free list keeps
        // across's soonest-first order (D1).
        val ordered =
            if (stopDistanceMeters.isEmpty()) {
                across
            } else {
                val deduped = DepartureRows.nearbyDeduped(across, stopDistanceMeters)
                DepartureRows.byStopDistance(deduped, stopDistanceMeters)
            }
        // Hide the service alerts the user has dismissed (until their content changes).
        DepartureRows.withoutDismissed(ordered, dismissed)
    }
    // Without the rows a journey card above already shows in full, then with the user's starred
    // services lifted to the top (SPEC D8). Warnings still lead on the location-free watched list; on
    // the near-me list (distances present) an alert is not hoisted, so a nearer stop is never pushed
    // below a farther one for carrying one.
    val rows = remember(nearbyRows, journeyRowsShown, starred, stopDistanceMeters) {
        DepartureRows.pinStarred(
            DepartureRows.withoutShownAbove(nearbyRows, journeyRowsShown),
            starred,
            warningsLead = stopDistanceMeters.isEmpty(),
        )
    }

    // The platform/pole the user drilled into by tapping its group header, or null for the full list
    // (SPEC D8): its stop ids (comma-joined; for a whole-station view, its cluster keys instead), its [StopGroup.splitKey], and its header text (the
    // app-bar fallback while no group matches), as saveable strings so the view survives rotation.
    // The filtered rows are rebuilt from the same snapshot on every recomposition, so the view stays
    // live and never fetches on its own (SPEC D4).
    var platformStopIds by rememberSaveable { mutableStateOf<String?>(null) }
    var platformKey by rememberSaveable { mutableStateOf("") }
    var platformTitle by rememberSaveable { mutableStateOf("") }
    // True when the view was opened from a place name: it is then the whole station, always titled
    // by the bare place name, however many groups it currently holds.
    var platformIsStation by rememberSaveable { mutableStateOf(false) }
    // The whole-station view a platform view was opened from, if any (its cluster keys and title),
    // so back steps out to the station rather than past it to the full list. Null when the platform
    // was opened straight from the full list.
    var parentStationIds by rememberSaveable { mutableStateOf<String?>(null) }
    var parentStationTitle by rememberSaveable { mutableStateOf("") }
    // Built from the platform's own stops WITHOUT the near-me fold: the fold keeps a line only at its
    // nearest stop, which would drop services from a farther platform — the drill-down shows all of
    // them. The stop ids alone aren't the platform: a station's platforms all come from one TfL stop,
    // so the rows are regrouped and only the tapped group is kept, with any closure alert for its
    // stops (Codex). It is matched on [StopGroup.splitKey] — the platform, pole letter, bearing or
    // compass — not [StopGroup.key], whose place part switches to a per-stop key while the stop
    // carries a line-status row (Codex). The saved stop ids already pin the place. Dismissals and
    // stars still apply, as on the full list. The title is resolved from the matched group each time, since a letterless bus
    // pole's qualifier (its shared terminus) can change with the departures (Codex).
    val platformView = remember(loaded?.stops, loaded?.lineStatuses, now, platformStopIds, platformKey, platformIsStation, starred, dismissed, rows, hiddenModes) {
        val ids = platformStopIds?.split(',')?.toSet() ?: return@remember null
        val ld = loaded ?: return@remember emptyList<DepartureRow>() to null
        // A station view saved its clusters, not stop ids, so each snapshot re-resolves its members —
        // a pole or platform that joins or leaves the cluster on a refresh is followed (Codex).
        val platformStops =
            if (platformIsStation) ld.stops.filter { stationClusterOf(it.clusterId, it.stopId) in ids }
            else ld.stops.filter { it.stopId in ids }
        val stopRows = DepartureRows.pinStarred(
            DepartureRows.withoutDismissed(
                HiddenModes.rows(DepartureRows.across(platformStops, now, ld.lineStatuses), hiddenModes),
                dismissed,
            ),
            starred,
        )
        val groups = StopGrouping.groupByStop(stopRows)
        // A header with no platform/pole to split on (a bare stop, or a directionless line-status
        // group beside a station's platforms) opens the whole stop: matching only its blank split
        // would show the warning without the stop's live departures (Codex).
        val matched = if (platformKey.isEmpty()) groups else groups.filter { it.splitKey == platformKey }
        // Plus the stops' directionless line-status rows (a suspended line with no predictions names
        // no platform, so it groups apart): which platform it would run from is unknown, so every
        // platform view of the stop shows it rather than hide a known suspension (SPEC principle 1).
        val groupRows = (matched + groups.filter { it.splitKey.isEmpty() && it.rows.all { r -> r.upcoming.isEmpty() } })
            .flatMapTo(HashSet()) { it.rows }
        // A whole-station view, or a whole-stop view spanning several groups, is titled by the bare
        // place, never by whichever platform happens to come first (Codex); a single group opened
        // from its own header keeps its full header text.
        val title = matched.firstOrNull()?.let { g ->
            // A station view keeps the name that was tapped: the cluster's members can carry different
            // cleaned names, and the first matched group depends on row order (Codex).
            if (platformIsStation) platformTitle
            else if (matched.size > 1) g.stopName
            else groupHeaderTitle(g.stopName, g.qualifier)
        }
        // The place's closure cards are the full list's own — already folded and dismissal-filtered —
        // so a card dismissed on either screen carries one identity and stays hidden on both (Codex).
        val places = platformStops.mapTo(HashSet()) { stopPlaceKey(it) }
        val closures = rows.filter { it.stopDisruption != null && stopPlaceKey(it) in places }
        (closures + stopRows.filter { it.stopDisruption == null && it in groupRows }) to title
    }
    // Close the view when the snapshot no longer holds the tapped group: its stops weren't fetched
    // (a near-me set re-resolved elsewhere), its last departure passed, or the feed dropped the
    // platform number it was keyed on. An empty page would assert "no departures" for a platform
    // that may still have trains (SPEC principle 1), so the full list shows instead — at once, not a
    // frame later — and the saved view is cleared.
    val platformGone = loaded != null && platformView != null && platformView.second == null
    // Leave the current view one level: a platform opened from a station returns to that station;
    // anything else returns to the full list.
    fun closeView() {
        val parent = parentStationIds
        parentStationIds = null
        if (parent != null && !platformIsStation) {
            platformStopIds = parent
            platformIsStation = true
            platformKey = ""
            platformTitle = parentStationTitle
        } else {
            platformStopIds = null
        }
    }
    // A vanished platform steps back to its station (which closes in turn if it is gone too).
    // Keyed on the view too: when a refresh drops both a platform and its station, closing to the
    // station leaves platformGone true, and the effect must run again to close that as well.
    LaunchedEffect(platformGone, platformStopIds, platformIsStation) { if (platformGone) closeView() }
    val platformRows = platformView?.first?.takeUnless { platformGone }
    val shownRows = platformRows ?: rows
    BackHandler(enabled = platformRows != null) { closeView() }
    // A searched station's page closes back to the search; a drill-down inside it steps out first
    // (this one is off while a drill-down is open, so the handler above takes that back).
    BackHandler(enabled = stationTitle != null && platformRows == null, onBack = onCloseStation)

    // The starred journey whose own view is open (its key), from a tap on its heading, or null. It
    // resolves against the current cards each recomposition, so it follows a swap and its trains stay
    // live; once the saved journeys are known and it isn't among them (unstarred), the view closes.
    val journeyViewCard = journeyViewKey?.let { key -> journeyCards.firstOrNull { it.journey.key == key } }
    LaunchedEffect(journeyViewKey, journeyViewCard == null, journeysLoading) {
        if (journeyViewKey != null && journeyViewCard == null && !journeysLoading) journeyViewKey = null
    }
    // Open while its card is shown, and while the saved journeys' first read is pending (after a
    // rotation they re-read from disk): the view then holds a placeholder rather than flashing to the
    // list, and Back still leaves it (Codex). A read that failed isn't pending, so the view closes
    // rather than spin forever (Codex).
    val journeyViewOpen = journeyViewKey != null && (journeyViewCard != null || journeysLoading)
    BackHandler(enabled = journeyViewOpen) { journeyViewKey = null }
    // A platform, station or journey view scrolls on its own, from the top when first opened. Each
    // open view keeps its own place — across a route page opened from it, a rotation, and a platform
    // opened from a station and backed out of (the station's place is still there) (Codex) — until
    // the full list is back; the full list keeps [listState].
    val drillScroll = rememberSaveable(saver = DrillScrollStates.Saver) { DrillScrollStates() }
    val drillOpen = platformStopIds != null || journeyViewKey != null
    val drillListState = drillScroll.stateFor("$journeyViewKey|$platformStopIds|$platformKey|$platformIsStation")
    LaunchedEffect(drillOpen) { if (!drillOpen) drillScroll.clear() }

    // The route whose detail is open, held by its stable row identity rather than the row object: a
    // saveable String survives a configuration change (the page stays open on rotation) and resets on
    // process death, and it re-resolves against the current `rows` each recomposition so the page
    // reflects a refreshed row and closes itself if the row leaves the list — the coordinate it shows
    // is never persisted (mirrors the bug-report flow).
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Which of the row's routes was tapped (a card shows one route row per destination), so the page
    // follows that route rather than whichever train is soonest. Two saveable strings, not a
    // RouteFocus, so it survives rotation with no custom Saver; null destination = no focus.
    var detailDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBranch by rememberSaveable { mutableStateOf<String?>(null) }
    // A journey card's train opens too: its farther origin isn't in the near-me rows, so the key is
    // also looked up among the journey cards' rows (shown only on the full list, as the cards are).
    val journeyRows = if (platformRows != null) emptyList() else journeyCards.flatMap {
        (it.state as? JourneyCardState.Trains)?.shownRows.orEmpty()
    }
    // And, last, among every loaded stop's rows: a page opened from a journey card stays open when
    // that journey is unstarred from the page itself, while its origin's departures are still loaded.
    val loadedRows = remember(loaded?.stops, loaded?.lineStatuses, now, dismissed) {
        val ld = loaded ?: return@remember emptyList()
        DepartureRows.withoutDismissed(DepartureRows.across(ld.stops, now, ld.lineStatuses), dismissed)
    }
    val detailRow = detailKey?.let { key ->
        shownRows.firstOrNull { it.detailKey() == key }
            ?: journeyRows.firstOrNull { it.detailKey() == key }
            ?: loadedRows.takeIf { platformRows == null }?.firstOrNull { it.detailKey() == key }
    }
    // When the open route's row leaves the list — its last departure passed on the 10s clock, or it
    // was pruned — clear the saved key so the vanished page stays closed rather than silently
    // reopening if a later refresh reproduced that same stop/line/direction identity (Codex). A live
    // refresh keeps the same identity, so this fires only on a genuine disappearance.
    LaunchedEffect(detailKey, detailRow == null) {
        if (detailKey != null && detailRow == null) detailKey = null
    }
    // Surface a failed star write as a snackbar (SPEC principle 2: a tap that didn't take isn't
    // swallowed). Gated on the list being shown — the snackbar host lives in the departures Scaffold,
    // not the full-screen route page below, so a failure while the page is open holds the flag until
    // the user returns, then shows, rather than firing at a host that isn't composed. Clear first,
    // then show, so a rotation while it's visible doesn't re-trigger it.
    // A hidden-modes change that didn't save holds only until the app restarts: say so, the same way
    // (SPEC principle 2).
    val hiddenModesWriteFailedMessage = stringResource(R.string.hidden_modes_write_failed)
    LaunchedEffect(hiddenModesWriteFailed, detailRow == null) {
        if (hiddenModesWriteFailed && detailRow == null) {
            onHiddenModesWriteFailureShown()
            snackbarHostState.showSnackbar(hiddenModesWriteFailedMessage)
        }
    }
    LaunchedEffect(starWriteFailed, detailRow == null) {
        if (starWriteFailed && detailRow == null) {
            onStarWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
    }
    // A failed journey-star write, surfaced like a failed row star (and gated the same way, since
    // the snackbar host lives in the departures Scaffold, not the route page the tap came from).
    LaunchedEffect(journeyWriteFailed, detailRow == null) {
        if (journeyWriteFailed && detailRow == null) {
            onJourneyWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
    }
    // Surface a failed dismiss write the same way as a failed star write, and gated the same way —
    // the snackbar host lives in the departures Scaffold, not the full-screen route page below, so a
    // failure while the page is open holds the flag until the user returns, then shows.
    val dismissWriteFailedMessage = stringResource(R.string.dismiss_write_failed)
    LaunchedEffect(dismissWriteFailed, detailRow == null) {
        if (dismissWriteFailed && detailRow == null) {
            // Clear-then-show, same reasoning as the star-write snackbar above.
            onDismissWriteFailureShown()
            snackbarHostState.showSnackbar(dismissWriteFailedMessage)
        }
    }
    // A full-screen page (its own app bar) that REPLACES the departures Scaffold, so it covers the
    // top bar and reads as a real destination rather than an overlay — the home for the star and the
    // line's full disruption text, and where the maps/nav hand-off will land (`TODO.md`).
    if (loaded != null && detailRow != null) {
        RouteDetailScreen(
            row = detailRow,
            isStarred = StarredRow.of(detailRow) in starred,
            // Same rule as the list card: only a timed row with starring available is pinnable.
            starrable = starringAvailable && detailRow.stopDisruption == null && detailRow.upcoming.isNotEmpty(),
            // Per this row, across BOTH uncertainty axes: its line wasn't determined (a blank id, or
            // one TfL omitted), OR its stop's own disruption lookup (a closure/move) failed. Either
            // leaves the row unchecked — clean only when TfL checked its line AND its stop's
            // disruption returned (SPEC principle 1).
            disruptionUnknown = detailRow.lineId.isBlank() ||
                detailRow.lineId !in loaded.determinedLineIds ||
                detailRow.stopId in loaded.stopsDisruptionUnknown,
            // This row's own age (the same per-row rule the card uses to withhold countdowns): a
            // stale snapshot's disruption status isn't presented as current (SPEC D4).
            stale = Staleness.isStale(Duration.between(detailRow.fetchedAt, now).toKotlinDuration()),
            now = now,
            onToggleStar = { onToggleStar(detailRow) },
            onBack = { detailKey = null },
            focus = detailDestination?.let { RouteFocus(it, detailBranch) },
            onDismissAlert = if (detailRow.status != null) {
                { onDismissAlert(detailRow) }
            } else {
                null
            },
            journeys = journeys,
            onToggleJourney = onToggleJourney,
            onDismissJourneyTip = onDismissJourneyTip,
        )
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // A journey's own view heads its content with the full journey instead: the bar
                    // shares its row with the freshness stamp, too narrow for both names.
                    if (journeyViewOpen) {
                        Unit
                    } else if (platformRows != null) {
                        // Elided from the start, so a narrow bar (it shares the row with the
                        // freshness stamp) keeps the platform — the part that tells platforms apart.
                        Text(
                            withArrowIcons(platformView?.second ?: platformTitle),
                            inlineContent = arrowInlineContent(LocalContentColor.current),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    } else {
                        if (stationTitle != null) {
                            // The station is the page's subject, so a long name shows as much as fits.
                            Text(stationTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            AppTitle()
                        }
                    }
                },
                navigationIcon = {
                    // A drilled-into platform view is a destination of its own: back returns to the
                    // full list (the system back does too — see the BackHandler above).
                    if (journeyViewOpen) {
                        IconButton(onClick = { journeyViewKey = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else if (platformRows != null) {
                        IconButton(onClick = { closeView() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else if (stationTitle != null) {
                        IconButton(onClick = onCloseStation) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else {
                        AppBarMark()
                    }
                },
                actions = {
                    FreshnessStamp(state, now, onRefresh)
                    // Refresh re-resolves the nearby set (a fresh location fix) as well as
                    // re-fetching departures, so walking to the next stop and pulling to refresh
                    // updates both — there's no separate locate control (SPEC *Finding stops*).
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    // Button and menu wrapped together so the dropdown anchors to the overflow
                    // button and opens from it; a bare DropdownMenu sibling anchors to the row
                    // slot instead and drops from the wrong place.
                    if (stationTitle == null) Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Box {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                                if (updateAvailable) {
                                    val updateDescription = stringResource(R.string.update_available)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            // Off-grid 2dp: an optical nudge seating the dot into
                                            // the icon's top-right corner (the standard badge spot).
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                            .semantics { contentDescription = updateDescription }
                                            .testTag(UPDATE_AVAILABLE_DOT_TAG),
                                    )
                                }
                            }
                        }
                        // The menu opens its own window, which doesn't inherit the theme's scaled
                        // density or pinch handler — FontSizeWindow re-applies the chosen size to
                        // the items and pinchFontSizeHost lets a pinch resize while it's open, so
                        // the size setting reaches the menu too (SPEC *Display size*). The host
                        // consumes only a two-finger pinch, so item taps are unaffected.
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.pinchFontSizeHost(),
                        ) {
                            FontSizeWindow {
                                if (updateAvailable) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.update_available)) },
                                        onClick = {
                                            menuExpanded = false
                                            onOpenAppListing()
                                        },
                                    )
                                }
                                if (onFindStation != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_find_station)) },
                                        onClick = {
                                            menuExpanded = false
                                            onFindStation()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_send_bug_report)) },
                                    onClick = {
                                        menuExpanded = false
                                        onSendBugReport()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_about)) },
                                    onClick = {
                                        menuExpanded = false
                                        showAbout = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier.fillMaxSize().padding(innerPadding)
        when (state) {
            DeparturesUiState.Loading -> Centered(content) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.departures_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                // A more direct update prompt than the top-bar overflow dot, which is easy to miss
                // while waiting on a cold load.
                if (updateAvailable) UpdateAvailableButton(onClick = onOpenAppListing)
            }

            is DeparturesUiState.Loaded -> if (journeyViewOpen && journeyViewCard == null) {
                // The saved journeys are still loading: a placeholder until the card is back.
                Centered(content) { CircularProgressIndicator() }
            } else if (journeyViewCard != null) {
                // A journey's own view: just its card, each group headed by where it boards, under
                // Swap and Unstar. Rendered from the same snapshot as the list (SPEC D4).
                LoadedContent(
                    state, now, onRefresh, refreshing, content, rows = emptyList(),
                    listState = drillListState,
                    journeyCards = listOf(journeyViewCard),
                    journeyView = true,
                    onFlipJourney = onFlipJourney,
                    onUnstarJourney = onToggleJourney,
                    onRetryJourneyRoutes = { journeyRouteRetry++ },
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = { row, focus ->
                        detailKey = row.detailKey()
                        detailDestination = focus?.destination
                        detailBranch = focus?.branch
                    },
                    onOpenSettings = onOpenSettings,
                    dismissed = dismissed,
                    onDismissAlert = onDismissAlert,
                )
            } else {
                LoadedContent(
                    state, now, onRefresh, refreshing, content, shownRows,
                    listState = if (platformRows != null) drillListState else listState,
                    // The platform view is one place: no distances (its header would only repeat the
                    // title) and no "More" paging of farther clusters.
                    stopDistanceMeters = if (platformRows != null) emptyMap() else stopDistanceMeters,
                    onOpenStopMap = onOpenStopMap,
                    // Journey cards sit atop the near-me list only, not a platform or station view.
                    // A far journey's card sits at the foot, once revealed, never among the near ones.
                    journeyCards = if (platformRows != null) emptyList() else journeyCards.filter { it.journey.key !in farJourneyMeters },
                    farJourneyCards = if (platformRows != null || !farRevealed) emptyList() else journeyCards.filter { it.journey.key in farJourneyMeters },
                    farJourneyMeters = farJourneyMeters,
                    onRevealFar = if (platformRows == null && !farRevealed && farJourneyMeters.isNotEmpty()) {
                        { farReveal.reveal() }
                    } else {
                        null
                    },
                    // Every nearby row is on a journey card above: nothing to call "no departures".
                    nearbyShownAbove = platformRows == null && rows.isEmpty() && nearbyRows.isNotEmpty(),
                    onFlipJourney = onFlipJourney,
                    onRetryJourneyRoutes = { journeyRouteRetry++ },
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    revealableModes = if (platformRows != null) {
                        emptySet()
                    } else {
                        revealableModes.filterNotTo(LinkedHashSet()) { HiddenModes.isHidden(it, hiddenModes) }
                    },
                    hiddenModes = hiddenModes,
                    onHideMode = onHideMode,
                    modesByPlace = placeModesShown,
                    onShowAllModes = onShowAllModes,
                    onReveal = onReveal,
                    onOpenDetail = { row, focus ->
                        detailKey = row.detailKey()
                        detailDestination = focus?.destination
                        detailBranch = focus?.branch
                    },
                    onOpenSettings = onOpenSettings,
                    dismissed = dismissed,
                    onDismissAlert = onDismissAlert,
                    // The full list and a whole-station view drill down to a platform; inside a platform
                    // view the header is inert. From a station, the station is remembered so back
                    // returns to it.
                    onOpenPlatform = if (platformRows != null && !platformIsStation) {
                        null
                    } else {
                        { group ->
                            if (platformRows != null) {
                                parentStationIds = platformStopIds
                                parentStationTitle = platformTitle
                            } else {
                                parentStationIds = null
                            }
                            platformStopIds = group.rows.mapTo(LinkedHashSet()) { it.stopId }.joinToString(",")
                            platformIsStation = false
                            platformKey = group.splitKey
                            platformTitle = groupHeaderTitle(group.stopName, group.qualifier)
                        }
                    },
                    // The whole station: the tapped place's clusters, keyed blank so the view keeps all of
                    // their stops' groups (see platformView). Membership is resolved from each snapshot's
                    // stops, not the groups on screen: the near-me fold can leave a sibling platform with
                    // no group, and a warned stop groups under a per-stop key (Codex).
                    onOpenStation = if (platformRows != null) {
                        null
                    } else {
                        { group ->
                            platformStopIds = group.rows.mapTo(LinkedHashSet()) { stationClusterOf(it.clusterId, it.stopId) }
                                .joinToString(",")
                            platformIsStation = true
                            parentStationIds = null
                            platformKey = ""
                            platformTitle = group.stopName
                        }
                    },
                    // A platform/station drill-down shows one place's stops, not the near-me set, so
                    // the "your location is low-confidence" banner doesn't apply there.
                    locationBanner = if (platformRows != null) null else locationBanner,
                    // A journey heading opens the journey's own view (from the full list only).
                    onOpenJourney = { journey -> journeyViewKey = journey.key },
                )
            }

            is DeparturesUiState.Error ->
                // Under the pull box with a scrollable child so a downward swipe refreshes
                // the error screen too (SPEC D6), not only the button.
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = content) {
                    Centered(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(errorMessage(state.kind)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                    }
                }
        }
    }
    if (showAbout) {
        AboutDialog(
            onOpenLicenses = {
                showAbout = false
                onOpenLicenses()
            },
            onDismiss = { showAbout = false },
        )
    }
}


/** A stop's station identity for the whole-station view: its StopArea cluster, else the stop alone. */
private fun stationClusterOf(clusterId: String, stopId: String): String = clusterId.ifBlank { "\u0000$stopId" }

/** The app's mark at the start of the departures app bar. */
@Composable
private fun AppBarMark() {
    // The app's route-lines mark on a themed tile — white in light, black in
    // dark — so it sits on the app bar without a fixed dark box. The arrow is a
    // separate, tintable layer flipped to contrast the tile (dark on white, white
    // on black); the colored lines stay put. The marks fill only the ~72/108
    // adaptive-icon safe zone, so requiredSize(48dp) draws them larger than the
    // 32dp box and the box clips back, rather than sitting tiny in the middle.
    // Decorative — the title already names the app, so no content description.
    // Follow the active Material theme (which may be dark from the system or an
    // explicit override), not the raw system setting, so the tile matches the
    // surface it sits on.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (darkTheme) Color.Black else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_appbar_route_lines),
            contentDescription = null,
            modifier = Modifier.requiredSize(48.dp),
        )
        Image(
            painter = painterResource(R.drawable.ic_appbar_route_arrow),
            contentDescription = null,
            modifier = Modifier.requiredSize(48.dp),
            colorFilter = ColorFilter.tint(if (darkTheme) Color.White else Color.Black),
        )
    }
}

@Composable
private fun LoadedContent(
    state: DeparturesUiState.Loaded,
    now: Instant,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier,
    // The rows to render, computed once by the caller so the list and the route-detail page share
    // the same grouping/ordering (SPEC D4 / D8).
    rows: List<DepartureRow>,
    listState: LazyListState = rememberLazyListState(),
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    onOpenStopMap: ((String, String) -> Unit)? = null,
    journeyCards: List<JourneyCard> = emptyList(),
    // True when the nearby list is empty only because the journey cards above already show it all.
    nearbyShownAbove: Boolean = false,
    onFlipJourney: (StarredJourney) -> Unit = {},
    onRetryJourneyRoutes: () -> Unit = {},
    onOpenJourney: ((StarredJourney) -> Unit)? = null,
    journeyView: Boolean = false,
    onUnstarJourney: ((StarredJourney) -> Unit)? = null,
    farJourneyCards: List<JourneyCard> = emptyList(),
    farJourneyMeters: Map<String, Double> = emptyMap(),
    onRevealFar: (() -> Unit)? = null,
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    starringAvailable: Boolean = true,
    revealableModes: Set<String> = emptySet(),
    onReveal: (String) -> Unit = {},
    // Open the full-screen route detail for a tapped card; the caller holds the open-route state.
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit = { _, _ -> },
    // Opens Settings from a National Rail line's "No key".
    onOpenSettings: () -> Unit = {},
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // Drill into one group's platform/pole; null disables the tap.
    onOpenPlatform: ((StopGroup) -> Unit)? = null,
    // Drill into the whole place of the tapped group, from a tap on the header's place name.
    onOpenStation: ((StopGroup) -> Unit)? = null,
    // Non-null when the shown stops are backed by a low-confidence location: a top banner says so
    // and offers "Try again" (runs [onRefresh], a re-locate). Null hides it.
    locationBanner: LocationBanner? = null,
    // The modes hidden from this list, their banner's "Show all", and the long-press "Hide ‹mode›"
    // (null on a list that doesn't offer it). See [MainScreen].
    hiddenModes: Set<String> = emptySet(),
    onHideMode: ((String) -> Unit)? = null,
    onShowAllModes: () -> Unit = {},
    // Each place's modes not yet hidden (by cluster), for a header's "Hide ‹mode›" items.
    modesByPlace: Map<String, Set<String>> = emptyMap(),
) {
    // Whether an empty list can be trusted as a real "no departures". It can only when
    // EVERY retained stop is fresh and the refresh was complete: a stale or un-refreshed
    // stop's empty rows might be expired predictions, not a true absence, and newer
    // services we couldn't fetch may exist (SPEC D4 / principle 1). So this reads every
    // stop's age and the partial/failure flags — not the freshest-stop stamp, which would
    // let one fresh stop mask a stale one's uncertainty. Per-row staleness (the withhold)
    // is decided per stop inside the card from that row's own age.
    val emptyStateUncertain = remember(state.stops, state.fetchedAt, state.partialRefresh, state.refreshFailure, now) {
        state.refreshFailure != null ||
            state.partialRefresh ||
            state.stops.any {
                Staleness.isStale(Duration.between(it.fetchedAt, now).toKotlinDuration())
            } ||
            // No retained stops to age individually — fall back to the snapshot stamp, so an
            // aged empty snapshot (e.g. one restored from storage) still prompts a refresh.
            (state.stops.isEmpty() && Staleness.isStale(Duration.between(state.fetchedAt, now).toKotlinDuration()))
    }
    // Pull-to-refresh over the whole loaded surface (SPEC D6).
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // The location behind these stops isn't current (SPEC *Finding stops*, principle 2):
            // either a stale last-known fix (APPROXIMATE) or a re-locate that couldn't update
            // (UPDATE_FAILED). Shown first — it frames every stop below — with a "Try again" that
            // re-locates (onRefresh). It clears once a fresh fix resolves.
            locationBanner?.let {
                ActionBanner(
                    text = stringResource(
                        when (it) {
                            LocationBanner.APPROXIMATE -> R.string.location_approximate
                            LocationBanner.UPDATE_FAILED -> R.string.location_update_failed
                        },
                    ),
                    onTryAgain = onRefresh,
                )
            }
            // Modes the user hid (SPEC *Finding stops → Hiding a mode*): one line saying which, so a
            // shorter list never passes for all there is, with "Show all" to bring them back.
            if (hiddenModes.isNotEmpty()) {
                ActionBanner(
                    text = stringResource(
                        R.string.modes_hidden,
                        hiddenModes.map(::modeName).sorted().joinToString(", "),
                    ),
                    actionLabel = stringResource(R.string.modes_show_all),
                    onAction = onShowAllModes,
                )
            }
            // Independent, not exclusive: a kept snapshot can be BOTH incomplete (a stop
            // was missing) and failed-to-refresh, and both facts have to stay visible —
            // collapsing them into one branch would drop the "stops missing" warning and
            // let the omitted stops go silent (SPEC principle 2).
            if (state.refreshFailure != null) {
                Banner(stringResource(refreshFailureMessage(state.refreshFailure)))
            }
            if (state.partialRefresh) {
                Banner(stringResource(R.string.partial_refresh))
            }
            // Arrivals loaded but their disruption status couldn't be checked — say so
            // rather than let the times read as verified-clean (SPEC *Disruptions*).
            if (state.disruptionUnknown) {
                Banner(stringResource(R.string.disruptions_unknown))
            }
            // Starred journeys still show when nothing nearby has departures: their origins can be
            // farther away, and hiding them behind "No departures" would drop live trains.
            if (rows.isEmpty() && journeyCards.isEmpty() && farJourneyCards.isEmpty() && onRevealFar == null) {
                // Scrollable even though it doesn't overflow: PullToRefreshBox reads the
                // pull from a scrollable child's nested-scroll events, so a plain Column
                // here would leave pull-to-refresh dead on the empty state (only the
                // button would work). verticalScroll forwards the gesture; the content
                // still centers.
                Centered(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // A stale snapshot with nothing left can't be read as "no departures"
                    // — the data is too old to trust that conclusion, and newer ones may
                    // exist (SPEC D4). Prompt a refresh instead of asserting an empty list.
                    Text(
                        // With modes hidden, an empty list says so rather than "no departures": the
                        // hidden modes may well be running (SPEC *Finding stops → Hiding a mode*).
                        text = when {
                            hiddenModes.isNotEmpty() -> stringResource(
                                R.string.modes_hidden_empty,
                                hiddenModes.map(::modeName).sorted().joinToString(", "),
                            )
                            emptyStateUncertain -> stringResource(R.string.departures_stale_empty)
                            else -> stringResource(R.string.departures_empty)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                    // Keep "More" reachable even when the nearest clusters returned nothing — that's
                    // exactly when the farther ones are most useful (SPEC principle 2).
                    MoreControls(revealableModes, onReveal, Modifier.padding(top = 16.dp))
                }
            } else {
                DepartureList(
                    rows, now, starred, onToggleStar, starringAvailable, stopDistanceMeters,
                    revealableModes, onReveal,
                    listState = listState,
                    onOpenSettings = onOpenSettings,
                    onHideMode = onHideMode,
                    modesByPlace = modesByPlace,
                    onOpenDetail = onOpenDetail,
                    onDismissAlert = onDismissAlert,
                    onOpenPlatform = onOpenPlatform,
                    onOpenStation = onOpenStation,
                    onOpenStopMap = onOpenStopMap,
                    journeyCards = journeyCards,
                    onFlipJourney = onFlipJourney,
                    onRetryJourneyRoutes = onRetryJourneyRoutes,
                    onOpenJourney = onOpenJourney,
                    journeyView = journeyView,
                    onUnstarJourney = onUnstarJourney,
                    farJourneyCards = farJourneyCards,
                    farJourneyMeters = farJourneyMeters,
                    onRevealFar = onRevealFar,
                    // Not in a journey's own view, nor when every nearby row is already on a journey
                    // card above.
                    nearbyEmptyNote = if (rows.isEmpty() && !journeyView && !nearbyShownAbove) {
                        // With modes hidden, say so rather than "no departures": they may be running.
                        if (hiddenModes.isNotEmpty()) {
                            stringResource(
                                R.string.modes_hidden_empty,
                                hiddenModes.map(::modeName).sorted().joinToString(", "),
                            )
                        } else {
                            stringResource(
                                if (emptyStateUncertain) R.string.departures_stale_empty else R.string.departures_empty_nearby,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FreshnessStamp(state: DeparturesUiState, now: Instant, onRefresh: () -> Unit) {
    val text = when (state) {
        // The placeholder frame (before any snapshot is read from disk) still carries a
        // stamp — a pending "Loading…" — so the top bar is present from the first frame and
        // fills in with the real age when the snapshot arrives (SPEC snapshot-render), rather
        // than the stamp popping in late.
        DeparturesUiState.Loading -> stringResource(R.string.loading_stamp)
        is DeparturesUiState.Loaded -> {
            val age = Duration.between(state.fetchedAt, now).toKotlinDuration()
            if (Staleness.isStale(age)) stringResource(R.string.stale_stamp)
            else stringResource(R.string.updated_stamp, RelativeTime.formatAge(age))
        }
        // An error has its own full-screen message (no snapshot, so no age to stamp).
        is DeparturesUiState.Error -> return
    }
    // The stamp is tappable too, so the "Tap to refresh" it shows when stale does what
    // it says (the Refresh action beside it is the always-present control).
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A [Banner] with a trailing "Try again" action — used for the low-confidence-location banner, where
 * the honest state is also actionable (re-locate). The text takes the remaining width so the action
 * stays a fixed trailing target; the button's own padding keeps the bar the same height as a plain
 * [Banner].
 */
@Composable
private fun ActionBanner(
    text: String,
    onTryAgain: () -> Unit = {},
    actionLabel: String = stringResource(R.string.try_again),
    onAction: () -> Unit = onTryAgain,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DepartureList(
    rows: List<DepartureRow>,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    stopDistanceMeters: Map<String, Double>,
    revealableModes: Set<String>,
    onReveal: (String) -> Unit,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    listState: LazyListState,
    onDismissAlert: (DepartureRow) -> Unit = {},
    // Opens Settings from a National Rail line's "No key" (SPEC *National Rail*).
    onOpenSettings: () -> Unit = {},
    // Hides a mode from a long press on a near-me row or header; null keeps long-press as starring.
    onHideMode: ((String) -> Unit)? = null,
    // Each place's modes not yet hidden (by cluster), for a header's "Hide ‹mode›" items.
    modesByPlace: Map<String, Set<String>> = emptyMap(),
    onOpenPlatform: ((StopGroup) -> Unit)? = null,
    onOpenStation: ((StopGroup) -> Unit)? = null,
    onOpenStopMap: ((String, String) -> Unit)? = null,
    journeyCards: List<JourneyCard> = emptyList(),
    onFlipJourney: (StarredJourney) -> Unit = {},
    onRetryJourneyRoutes: () -> Unit = {},
    // Opens a journey's own view from a tap on its heading; null leaves the heading inert.
    onOpenJourney: ((StarredJourney) -> Unit)? = null,
    // The faraway journeys' cards once revealed, with each one's meters to its nearer end; and the
    // button that reveals them, null once they are (or when there are none).
    farJourneyCards: List<JourneyCard> = emptyList(),
    farJourneyMeters: Map<String, Double> = emptyMap(),
    onRevealFar: (() -> Unit)? = null,
    // True in a journey's own view: the title bar names the journey, so its heading is dropped, and
    // each of its groups is headed by its platform/pole so the rider sees where to board.
    journeyView: Boolean = false,
    // Unstars a journey from its own view's action row; null hides the button.
    onUnstarJourney: ((StarredJourney) -> Unit)? = null,
    // Why the near-me part is empty, shown under the journey cards when there are no nearby rows.
    nearbyEmptyNote: String? = null,
    modifier: Modifier,
) {
    // Stop-closure alerts render as standalone cards at the top of the list — warnings lead (the
    // caller ordered them first). Each carries its own place heading (its interchange, else its
    // stop), so it needs no group header and no distance label above it (SPEC *Disruptions*). The
    // remaining rows (timed and line-status) cluster into per-place groups so
    // each gets a name header — the flat list gives a card no boarding location once >1 place is
    // on screen (SPEC D8). Pure and cheap; the caller ordered the rows.
    val closureRows = remember(rows) { rows.filter { it.stopDisruption != null } }
    // Pass the full row set: groupByStop groups only the non-closure rows but counts each closure
    // alert's stop as a place, so a lone departures group beside a closure-only stop still shows
    // its header (SPEC *Disruptions*).
    // On the near-me list (distances present) a place is ordered by distance, not lifted for
    // carrying a line-status alert; the watched list keeps warnings leading (D1, SPEC *Disruptions*).
    val groups = remember(rows, stopDistanceMeters) {
        StopGrouping.groupByStop(rows, warningsLead = stopDistanceMeters.isEmpty())
    }
    // One place-wide header distance per cluster: the nearest of ALL the place's members, shared by
    // every direction group of that place — so a station split into direction headers shows one
    // consistent distance (its nearest member), not each direction's own members' nearest, which
    // could differ or read farther than the place's nearest (Codex P2, PR #109). Empty on the
    // watched list (no distances, D1).
    val placeDistanceMeters = remember(groups, stopDistanceMeters) {
        if (stopDistanceMeters.isEmpty()) {
            emptyMap()
        } else {
            groups.groupBy { it.placeKey }.mapNotNull { (place, placeGroups) ->
                placeGroups.asSequence()
                    .flatMap { it.rows.asSequence() }
                    .mapNotNull { stopDistanceMeters[it.stopId] }
                    .minOrNull()
                    ?.let { place to it }
            }.toMap()
        }
    }
    // A journey's heading (or, in its own view, its actions), closure notices, and trains or note —
    // shared by the near journeys at the top and the revealed faraway ones at the bottom, whose
    // headings also carry their distance ([farMeters]).
    fun LazyListScope.journeyItems(cards: List<JourneyCard>, farMeters: Map<String, Double>? = null) {
            cards.forEachIndexed { index, card ->
                if (journeyView) {
                    item(key = "journey-actions|${card.journey.key}") {
                        JourneyActions(
                            card.journey,
                            onSwap = { onFlipJourney(card.journey) },
                            onUnstar = onUnstarJourney?.let { unstar -> { unstar(card.journey) } },
                        )
                    }
                } else {
                    item(key = "journey-header|${card.journey.key}") {
                        JourneyHeader(
                            card.journey,
                            // First on screen, or first under the "Faraway favorites" label: no break.
                            firstOnScreen = index == 0,
                            onSwap = { onFlipJourney(card.journey) },
                            onOpen = onOpenJourney?.let { open -> { open(card.journey) } },
                            distanceLabel = farMeters?.get(card.journey.key)?.let(StopDistance::label),
                        )
                    }
                }
                // The origin's closure notice rides the journey card: a farther origin isn't on the near-me
                // list, so without it the trains below would read as catchable at a closed station.
                card.closures.forEach { rows ->
                    val closure = rows.first()
                    item(key = "journey-closure|${card.journey.key}|${closure.stopId}") {
                        // Dismissed on every pole that carries it, so the next pole's copy doesn't take its place.
                        StopClosureCard(closure, onDismiss = { rows.forEach(onDismissAlert) })
                    }
                }
                if (card.destinationUnchecked) {
                    item(key = "journey-destination-unchecked|${card.journey.key}") {
                        JourneyNote(stringResource(R.string.journey_destination_unchecked, card.journey.to.name))
                    }
                }
                when (val state = card.state) {
                    is JourneyCardState.Trains -> if (state.rows.isEmpty()) {
                        // Only trains to change from: "no direct trains" unless some departure couldn't be
                        // checked (it may be direct), which the note below says instead.
                        if (state.changes.isEmpty() || !state.incomplete) {
                            item(key = "journey-none|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(
                                        when {
                                            state.changes.isNotEmpty() -> R.string.journey_none_direct
                                            card.journey.bus -> R.string.journey_none_bus
                                            else -> R.string.journey_none
                                        },
                                        card.journey.to.name,
                                    ),
                                )
                            }
                        }
                        journeyChanges(card, state, now, starred, onToggleStar, starringAvailable, onOpenDetail, onOpenSettings)
                        if (state.incomplete) {
                            item(key = "journey-note|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(R.string.journey_incomplete),
                                    onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                                )
                            }
                        }
                    } else {
                        // Any bus boarding elsewhere than the origin (a pole beside it), even when it's the
                        // only one: each stop's buses go under its own heading and pole letter, so none
                        // reads as leaving from the stop the rider is at. The journey's own view heads
                        // every group that way ("King's Cross St. Pancras – Platform 7"), so it shows
                        // where to board; the list's card otherwise leaves that to the journey heading.
                        val severalStops = state.rows.any { it.stopId != card.boardingIds.firstOrNull() }
                        StopGrouping.groupByStop(state.rows, warningsLead = false).forEachIndexed { groupIndex, group ->
                            if (severalStops || journeyView) {
                                item(key = "journey-stop|${card.journey.key}|${group.key}") {
                                    StopGroupHeader(
                                        group.stopName,
                                        group.qualifier,
                                        distanceLabel = null,
                                        // In the journey view the first group heads the page under the
                                        // actions, with no group break above it.
                                        firstOnScreen = journeyView && groupIndex == 0,
                                    )
                                }
                            }
                            item(key = "journey-card|${card.journey.key}|${group.key}") {
                                StopGroupCard(
                                    group,
                                    now,
                                    starred = starred,
                                    onToggleStar = onToggleStar,
                                    starringAvailable = starringAvailable,
                                    onOpenDetail = onOpenDetail,
                                    onOpenSettings = onOpenSettings,
                                )
                            }
                        }
                        // A suspended line's status row is no direct train: with trains to change from, say so under it.
                        if (state.changes.isNotEmpty() && !state.incomplete && !Journeys.directDue(state.rows)) {
                            item(key = "journey-none|${card.journey.key}") {
                                JourneyNote(stringResource(R.string.journey_none_direct, card.journey.to.name))
                            }
                        }
                        journeyChanges(card, state, now, starred, onToggleStar, starringAvailable, onOpenDetail, onOpenSettings)
                        if (state.incomplete) {
                            item(key = "journey-note|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(R.string.journey_incomplete),
                                    onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                                )
                            }
                        }
                    }
                    JourneyCardState.Checking -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(
                            stringResource(if (card.journey.bus) R.string.journey_checking_bus else R.string.journey_checking),
                        )
                    }
                    is JourneyCardState.NotChecked -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(
                            stringResource(if (card.journey.bus) R.string.journey_not_checked_bus else R.string.journey_not_checked),
                            onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                        )
                    }
                    JourneyCardState.RouteFailed -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(stringResource(R.string.journey_route_failed), onRetry = onRetryJourneyRoutes)
                    }
                }
            }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A closure alert keys on its stop and hub so a recycled row can't carry another
        // alert's expanded state onto it.
        // Starred journeys lead the list (SPEC *Journeys*): each a header naming the direction shown,
        // tappable to show the other, over a card of just the trains that call at the far end.
        journeyItems(journeyCards)
        nearbyEmptyNote?.let { note ->
            item(key = "nearby-empty") { JourneyNote(note) }
        }
        items(closureRows, key = { "closure|${it.stopId}|${it.hubId}" }) { row ->
            StopClosureCard(row, onDismiss = { onDismissAlert(row) })
        }
        // One combined one-line header per group, then the group's card (SPEC D8): the place name and
        // its platform/pole qualifier read on one title-case line ("King's Cross St. Pancras – Platform
        // 1 (120 m)"), and every route of the group's stop(s) sits as an interior row of the one card
        // below. The place name repeats on each platform header of a station (as does the distance) —
        // the near-me rider judges each platform on its own line.
        groups.forEachIndexed { index, group ->
            // The near-me list carries a per-stop distance; the watched list doesn't, so the label is
            // present only when this place's stops are in the map (D1). A place groups several stops (a
            // junction's poles, a station's platforms), so it shows the distance to the *closest* of
            // them — the one a rider walks to (placeDistanceMeters).
            val distanceLabel = placeDistanceMeters[group.placeKey]?.let(StopDistance::label)
            // Draw the header when the grouping distinguishes this place (>1 place, a split into
            // platforms/poles, a closure) OR there's a distance to promise. A lone bare near-me place
            // still shows its name and distance, else a one-place result would drop both (Codex, PR
            // #82). The first group on screen takes no extra top break — unless a closure alert precedes
            // it.
            if (group.showHeader || distanceLabel != null) {
                item(key = "header|${group.key}") {
                    StopGroupHeader(
                        group.stopName,
                        group.qualifier,
                        distanceLabel,
                        firstOnScreen = index == 0 && closureRows.isEmpty() && journeyCards.isEmpty(),
                        onClick = onOpenPlatform?.let { open -> { open(group) } },
                        onNameClick = onOpenStation?.let { open -> { open(group) } },
                        // The distance opens the group's own nearest stop in the maps app — for a
                        // bus place that's this pole, the one the rider walks to.
                        onDistanceClick = onOpenStopMap?.let { open ->
                            group.rows
                                .mapNotNull { r -> stopDistanceMeters[r.stopId]?.let { r.stopId to it } }
                                .minByOrNull { it.second }
                                ?.let { (stopId, _) -> { open(stopId, group.stopName) } }
                        },
                        hideModes = if (onHideMode != null) headerModes(group, modesByPlace) else emptyList(),
                        onHideMode = onHideMode,
                    )
                }
            }
            item(key = "card|${group.key}") {
                StopGroupCard(
                    group,
                    now,
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = onOpenDetail,
                    onOpenSettings = onOpenSettings,
                    onHideMode = onHideMode,
                )
            }
        }
        // The faraway journeys (SPEC *Journeys*), at the foot like the "More" stops: a button, then,
        // once tapped, their cards under a "Faraway favorites" label (revealing is what fetches them).
        if (farJourneyCards.isNotEmpty()) {
            item(key = "far-journeys-label") {
                Text(
                    text = stringResource(R.string.journeys_faraway),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                )
            }
            journeyItems(farJourneyCards, farJourneyMeters)
        } else if (onRevealFar != null) {
            item(key = "far-journeys-reveal") {
                TextButton(onClick = onRevealFar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.journeys_faraway))
                }
            }
        }
        if (revealableModes.isNotEmpty()) {
            // A per-mode "More" footer pages the farther clusters on demand (SPEC principle 2).
            // The extra top padding, past the list's 8dp item gap, sets the footer apart from the
            // last group — asymmetric on purpose: it's a trailing section, not another row.
            item(key = "more-controls") {
                MoreControls(revealableModes, onReveal, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The per-mode "More" controls at the foot of the near-me list (SPEC *Finding stops → Near me
 * now*): one full-width button per mode still holding an unrevealed farther cluster, tapping which
 * pages that mode's next clusters in. Rendered nowhere when [revealableModes] is empty. Named modes
 * come first (alphabetical), the generic bucket (a modeless cluster) last, for a stable order.
 * A `TextButton` carries Material's ≥48dp interactive touch target, clearing the 44dp floor.
 */
@Composable
private fun MoreControls(revealableModes: Set<String>, onReveal: (String) -> Unit, modifier: Modifier = Modifier) {
    if (revealableModes.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in revealableModes.sortedWith(compareBy({ it.isEmpty() }, { it }))) {
            TextButton(onClick = { onReveal(mode) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(moreLabelRes(mode)))
            }
        }
    }
}

/** The "More …" label for a reveal bucket — a dedicated string per mode the nearby search can
 *  return (its `StopFinder.DEFAULT_NEARBY_STOP_TYPES`), so two revealable buckets never share the
 *  generic "More stops" and become indistinguishable (Codex, PR #87). The generic label is left for
 *  the modeless bucket ([NearbySelection.GENERIC_MORE]) and any mode outside that fetched set. */
internal fun moreLabelRes(mode: String): Int = when (mode) {
    "bus" -> R.string.more_stops_bus
    "tube" -> R.string.more_stops_tube
    "dlr" -> R.string.more_stops_dlr
    "overground" -> R.string.more_stops_overground
    "elizabeth-line" -> R.string.more_stops_elizabeth
    "tram" -> R.string.more_stops_tram
    "national-rail" -> R.string.more_stops_rail
    "coach" -> R.string.more_stops_coach
    "river-bus" -> R.string.more_stops_river
    else -> R.string.more_stops
}

/**
 * The one combined header above a group's card (SPEC D8): the place name and its platform/pole
 * qualifier on a single title-case line — "King's Cross St. Pancras – Platform 1", "Cranley Gardens –
 * Stop G", "Highgate – Eastbound" — with [distanceLabel] ("(120 m)", null on the location-free watched
 * list, D1) appended dimmed. No small caps, no tracking: the place name and the qualifier share one
 * [MaterialTheme.typography.labelLarge] / [FontWeight.SemiBold] / `onSurface` style, and only the
 * distance is muted to `onSurfaceVariant`.
 *
 * The place name is the one element that clips: it takes the row's slack and ellipsizes when long,
 * while the " – <qualifier>" and " (<distance>)" are reserved (measured first) so they stay fully
 * visible — a long "King's Cross St. Pancras – Platform 7 (120 m)" keeps "– Platform 7 (120 m)" and
 * clips the name. The whole row announces one screen-reader label — the place name, the spoken
 * qualifier (which keeps the direction/towards the visible segment drops), then the distance. Extra
 * top space marks the break between groups; the first on screen takes none.
 */
@Composable
private fun StopGroupHeader(
    name: String,
    qualifier: StopQualifier?,
    distanceLabel: String?,
    firstOnScreen: Boolean,
    // Opens this group's platform view; null leaves the header inert.
    onClick: (() -> Unit)? = null,
    // Opens the whole place from a tap on the name itself (the rest of the row opens the platform).
    // Null leaves the name to the row.
    onNameClick: (() -> Unit)? = null,
    // Shows the stop in the maps app from a tap on the distance. Null leaves the distance to the row.
    onDistanceClick: (() -> Unit)? = null,
    // A place name stays one line; a journey's change heading wraps rather than lose its "(for …)".
    nameMaxLines: Int = 1,
    // What a screen reader hears for [name] when it differs ("King's Cross to Camden Town, for High
    // Barnet", never the drawn arrow). Null reads [name].
    spokenName: String? = null,
    // The modes a long press on the header offers to hide (SPEC *Finding stops → Hiding a mode*);
    // empty or a null [onHideMode] leaves the header without a long press.
    hideModes: List<String> = emptyList(),
    onHideMode: ((String) -> Unit)? = null,
) {
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val openLabel = stringResource(R.string.action_show_platform)
    val stationLabel = stringResource(R.string.action_show_station)
    val mapLabel = stringResource(R.string.action_show_on_map)
    val label = remember(qualifier) { groupHeaderLabel(qualifier) }
    // The full spoken label: the place name, the spoken qualifier (direction/towards kept), then the
    // distance — read as one, so a screen reader hears the whole header rather than three fragments.
    val spoken = remember(name, spokenName, qualifier, distanceLabel) {
        buildString {
            append(spokenName ?: name)
            groupHeaderSpoken(qualifier)?.let { append(", ").append(it) }
            distanceLabel?.let { append(", ").append(it) }
        }
    }
    val hideable = onHideMode != null && hideModes.isNotEmpty()
    var hideMenuOpen by remember { mutableStateOf(false) }
    // The latest tap action, read by a detector keyed only on whether there is one: the caller
    // builds [onClick] afresh on every recomposition (the clock ticks every few seconds), and keying
    // the detector on it would restart it and drop a long press in progress, as [RouteRow] avoids.
    val currentOnClick by rememberUpdatedState(onClick)
    val tappable = onClick != null
    val moreLabel = stringResource(R.string.more_actions)
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The tap target spans the full row including the group-break space above the text, so
            // the header grows no taller for being tappable and the list keeps its spacing.
            .then(
                if (hideable) {
                    // A tap opens the platform, a long press the "Hide ‹mode›" menu — the same
                    // gesture handling as a route row, with both actions announced.
                    Modifier
                        .pointerInput(tappable) {
                            detectTapGestures(
                                onTap = { currentOnClick?.invoke() },
                                onLongPress = { hideMenuOpen = true },
                            )
                        }
                        .semantics {
                            if (tappable) onClick(label = openLabel) { currentOnClick?.invoke(); true }
                            onLongClick(label = moreLabel) { hideMenuOpen = true; true }
                        }
                } else if (onClick != null) {
                    Modifier.clickable(onClickLabel = openLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 4.dp, end = 4.dp, top = if (firstOnScreen) 0.dp else 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The distance ("(120 m)") is short and reserved (unweighted, measured first). The name and
        // the qualifier then SHARE the rest, each weighted, so neither can consume the whole row and
        // crowd the other to zero: a long place name and a long qualifier each keep at least their
        // half and clip within it, so the rider's boarding place always stays visible (Codex P1, PR
        // #122). `fill = false` lets a short name/qualifier sit at its natural width and pack left
        // rather than pad out its half. The name ellipsizes; the qualifier hard-clips its glyphs.
        Text(
            // A journey's change heading carries the "heading to" arrow ("King's Cross ➔ Camden Town").
            text = withArrowIcons(name),
            inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = nameMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                // The name is its own tap target (child first, so it wins over the row's platform tap)
                // and its own screen-reader button, read as the place name with a "whole station" hint.
                .then(if (onNameClick != null) Modifier.clickable(onClickLabel = stationLabel, onClick = onNameClick) else Modifier),
        )
        if (label != null) {
            Text(
                text = withArrowIcons("${groupHeaderJoin(label)}$label"),
                inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                // Elide with a single "…" (never a mid-glyph cut) when the name and qualifier can't
                // both fit their shared half of the row.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (distanceLabel != null) {
            Text(
                text = " ($distanceLabel)",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                // Its own tap target (child first, so it wins over the row's platform tap) and its own
                // screen-reader button, like the place name.
                modifier = if (onDistanceClick != null) {
                    Modifier.clickable(onClickLabel = mapLabel, onClick = onDistanceClick)
                } else {
                    Modifier
                },
            )
        }
    }
    if (hideable) {
        HideModeMenu(
            expanded = hideMenuOpen,
            onDismiss = { hideMenuOpen = false },
            modes = hideModes,
            onHideMode = { mode -> onHideMode?.invoke(mode) },
        )
    }
    }
}

/**
 * The long-press menu of a near-me header or row (SPEC *Finding stops → Hiding a mode*): an
 * optional [leading] item (a row's pin/unpin), then "Hide ‹mode›" for each of [modes]. Wrapped in
 * [FontSizeWindow] like the overflow menu, so the chosen text size reaches it.
 */
@Composable
private fun HideModeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modes: List<String>,
    onHideMode: (String) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.pinchFontSizeHost()) {
        FontSizeWindow {
            leading?.invoke()
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.hide_mode, modeName(mode))) },
                    onClick = {
                        onDismiss()
                        onHideMode(mode)
                    },
                )
            }
        }
    }
}

/**
 * The modes a header's place serves, in a stable order, for its "Hide ‹mode›" items: from every
 * stop of the place — its cluster ([placeModes]), so a sibling platform or pole counts too — by
 * their served lines and departures, not just the rows on screen, so a mode with no train due can
 * still be hidden. A stop closure has no mode of its own to hide.
 */
private fun headerModes(group: StopGroup, placeModes: Map<String, Set<String>>): List<String> =
    group.rows.asSequence()
        .flatMap { row ->
            val served = placeModes[placeOf(row.clusterId, row.stopId)].orEmpty()
            if (row.stopDisruption == null && row.mode.isNotBlank()) served + row.mode else served
        }
        .distinctBy { it.lowercase() }
        .sortedBy(::modeName)
        .toList()

/**
 * Each place's modes, by cluster (a stop in none is its own place), from its stops' served lines
 * and departures, for [headerModes].
 */
internal fun placeModes(stops: List<StopArrivals>): Map<String, Set<String>> {
    val modes = HashMap<String, LinkedHashSet<String>>()
    for (stop in stops) {
        val place = modes.getOrPut(placeOf(stop.clusterId, stop.stopId)) { LinkedHashSet() }
        (stop.lines.map { it.mode } + stop.departures.map { it.mode }).filterTo(place) { it.isNotBlank() }
    }
    return modes
}

private fun placeOf(clusterId: String, stopId: String): String = clusterId.ifBlank { "\u0000stop:$stopId" }

/**
 * A stop-closure alert as its own header-less card at the top of the list (SPEC *Disruptions*): the
 * disruption in an error-toned surface titled by the interchange (else the stop), the body collapsed
 * to its first line and expanded on tap. Kept as a standalone card — a whole-stop closure has no
 * platform/pole qualifier to head, and its departures (if any) show in the group cards below.
 */
@Composable
private fun StopClosureCard(row: DepartureRow, onDismiss: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // A traversal group, matching the departure cards. The place name is stripped from the body
        // at display (not upstream) so the near-me fold's identity stays member-independent
        // (cleanDisruptionBody); the title supplies the stop name a bus "Bus Stop Closed" never does.
        Column(modifier = Modifier.padding(16.dp).semantics { isTraversalGroup = true }) {
            StopClosureContent(
                cleanDisruptionBody(
                    row.stopDisruption.orEmpty(),
                    stopName = row.stopName,
                    hubName = row.hubName,
                    aliases = row.placeAliases,
                ),
                title = row.hubName.ifBlank { row.stopName },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * One card per group (SPEC D8): an [OutlinedCard] whose body is a [Column] of interior **route
 * rows** — one per destination line of each of the group's rows — separated by thin dividers. Each
 * route row carries its line pill, its destination, an inline ⚠ when the line is disrupted, and the
 * merged countdown; a starred route wears a gold leading-edge bar (the per-row accent that replaces
 * the old whole-card gold border, which no longer maps now a card holds several routes).
 *
 * Interactions live on each route row, not the card: a TAP opens the detail view and a LONG-PRESS
 * pins (where starrable). Staleness is per row, from the row's own stop age, so a stale stop
 * withholds its countdowns ("?") while a fresh stop's card beside it stays live (SPEC D4).
 */
/**
 * A starred journey as shown on the near-me list: [journey] turned to the direction shown, and its
 * [rows] — the trains from its origin that call at its destination ([Journeys.rows]). Null rows while
 * the origin's departures or the line's route aren't in yet, so the card says it's checking rather
 * than claim there are no trains (SPEC principle 1).
 */
internal data class JourneyCard(
    val journey: StarredJourney,
    val state: JourneyCardState,
    // The (undismissed) closure notices of its boarding stops and far end, shown whatever the trains'
    // state: each one notice, as the rows of every pole that carries it (dismissed together).
    val closures: List<List<DepartureRow>> = emptyList(),
    // Every departure a complete check judged (empty otherwise), for the widget, by boarding stop.
    val checked: Map<String, Set<JourneyCall>> = emptyMap(),
    // The stops it boards from: its origin, then any neighboring poles (empty until placed).
    val boardingIds: List<String> = emptyList(),
    // Its far-end stops whose closure is checked: its own, the route's, and any a departure reaches.
    val destinationIds: Set<String> = emptySet(),
    // Some far-end stop's closure check failed with nothing known, so it may be closed.
    val destinationUnchecked: Boolean = false,
)

/** What a journey card can say about its trains. */
internal sealed interface JourneyCardState {
    /** The origin's departures or the line's route aren't in yet. */
    data object Checking : JourneyCardState

    /** The line's route couldn't be loaded, so which trains call at the far end is unknown. */
    data object RouteFailed : JourneyCardState

    /**
     * The origin's last refresh failed or has gone stale, and there's nothing current to show. [retry]
     * when a lookup a retry can redo failed (the poles beside a bus journey's origin).
     */
    data class NotChecked(val retry: Boolean = false) : JourneyCardState

    /**
     * The trains that call at the far end — empty only from a fresh, current fetch. [incomplete]
     * when some departure or line couldn't be checked, so the list may be missing one.
     */
    data class Trains(
        val rows: List<DepartureRow>,
        val incomplete: Boolean = false,
        // [incomplete] because some line's route failed to load: offer a retry.
        val retry: Boolean = false,
        // Trains on another branch, with where to change, when no direct train is due.
        val changes: List<JourneyChange> = emptyList(),
    ) : JourneyCardState {
        /**
         * Every row the card shows, the direct trains and those to change from, with the two parts of
         * one row (the same stop, line, direction and platform) joined again: the near-me list's
         * no-repeat check and a tapped train's route page each look for the whole row.
         */
        val shownRows: List<DepartureRow>
            get() = (rows + changes.map { it.row })
                .groupBy { listOf(it.stopId, it.lineId, it.directionKey, it.platform) }
                .values
                .map { parts ->
                    if (parts.size == 1) {
                        parts.single()
                    } else {
                        val upcoming = parts.flatMap { it.upcoming }.distinct().sortedBy { it.expectedArrival }
                        parts.first().copy(upcoming = upcoming, destination = upcoming.first().destination)
                    }
                }
    }
}

/**
 * A journey card's trains on another branch ([JourneyCardState.Trains.changes]), under a "King's Cross
 * ➔ Camden Town (for High Barnet)" heading per change stop, each boarding stop's in its own card like the direct trains.
 */
private fun LazyListScope.journeyChanges(
    card: JourneyCard,
    state: JourneyCardState.Trains,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    state.changes.groupBy { it.stopId }.forEach { (stopId, changes) ->
        item(key = "journey-change|${card.journey.key}|$stopId") {
            StopGroupHeader(
                stringResource(R.string.journey_change_at, card.journey.from.name, changes.first().stopName, card.journey.to.name),
                qualifier = null,
                distanceLabel = null,
                firstOnScreen = false,
                nameMaxLines = 2,
                spokenName = stringResource(
                    R.string.journey_change_at_spoken,
                    card.journey.from.name,
                    changes.first().stopName,
                    card.journey.to.name,
                ),
            )
        }
        StopGrouping.groupByStop(changes.map { it.row }, warningsLead = false).forEach { group ->
            item(key = "journey-change-card|${card.journey.key}|$stopId|${group.key}") {
                StopGroupCard(
                    group,
                    now,
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = onOpenDetail,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

/** A journey card's one-line status in place of trains, with a retry when there's one to offer. */
@Composable
private fun JourneyNote(text: String, onRetry: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}

/**
 * A journey's own view's heading and actions, above its trains: the journey in full, then swap the
 * direction shown, and unstar the journey
 * (which closes the view, as its card is gone). Labeled buttons rather than bare icons — this is the
 * view a rider opens to act on the journey.
 */
@Composable
private fun JourneyActions(journey: StarredJourney, onSwap: () -> Unit, onUnstar: (() -> Unit)?) {
    val spoken = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    Column {
        // The journey in full, wrapping as far as it needs, so both names show however long they are
        // and at any text size (Codex).
        Text(
            text = withArrowIcons(stringResource(R.string.journey_title, journey.from.name, journey.to.name)),
            inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                .semantics { heading(); contentDescription = spoken },
        )
        // Wraps, so at a large text size or on a narrow screen Unstar drops to its own line rather
        // than being squeezed or clipped (Codex).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSwap) {
                Icon(SwapIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_flip_journey), modifier = Modifier.padding(start = 8.dp))
            }
            if (onUnstar != null) {
                OutlinedButton(onClick = onUnstar) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = LocalStarredBorderColor.current,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.action_unstar_journey), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * A journey card's heading, "Highgate ➔ King's Cross St. Pancras ★": the direction shown, styled like
 * a stop group header (same weight and group-break space) so the list reads as one. A tap on the
 * heading opens the journey's own view ([onOpen], null leaves it inert — as in that view); the ⇄
 * button at the end swaps the direction in place ([onSwap]), for planning the way back without
 * leaving the list (maintainer, 2026-09-24).
 */
@Composable
private fun JourneyHeader(
    journey: StarredJourney,
    firstOnScreen: Boolean,
    // Null hides the ⇄ (a far journey's collapsed heading: nothing below it to swap).
    onSwap: (() -> Unit)?,
    onOpen: (() -> Unit)? = null,
    // How far away a collapsed far journey is ("2.4 km"), dimmed after the star; null for none.
    distanceLabel: String? = null,
) {
    val openLabel = stringResource(R.string.action_open_journey)
    val swapLabel = stringResource(R.string.action_flip_journey)
    val title = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    val spoken = distanceLabel?.let { "$title, $it" } ?: title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (firstOnScreen) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen) else Modifier)
                // Read "Highgate to King's Cross", not the arrow's name.
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = withArrowIcons(stringResource(R.string.journey_title, journey.from.name, journey.to.name)),
                inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The gold star marks a starred journey, telling its heading apart from a bus place's
            // "Place ➔ Destination" header (maintainer, 2026-09-24). Decorative: the card is a journey
            // by construction, so it adds nothing to the spoken label.
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = LocalStarredBorderColor.current,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            if (distanceLabel != null) {
                Text(
                    text = " ($distanceLabel)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (onSwap == null) return@Row
        // Compact on purpose (maintainer, 2026-09-24): a 24dp target with no 48dp minimum, so the
        // heading keeps a header's height while the swap control is still being tried out. The
        // journey's own view carries a full-size Swap direction button.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButton(onClick = onSwap, modifier = Modifier.size(24.dp)) {
                Icon(
                    SwapIcon,
                    contentDescription = swapLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StopGroupCard(
    group: StopGroup,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    onOpenSettings: () -> Unit = {},
    // Offers "Hide ‹mode›" in each row's long-press menu; null keeps long-press as starring.
    onHideMode: ((String) -> Unit)? = null,
) {
    // Cap the line pill at half the card's inner width, so a long name at a large font scale
    // ellipsizes rather than consuming the card and starving the countdown, which must stay one line
    // (SPEC D8). Inner width ≈ screen minus the list's 16dp side padding and the row's 16dp padding.
    val cardInnerWidth = LocalConfiguration.current.screenWidthDp.dp - 64.dp
    val pillModifier = Modifier.widthIn(max = cardInnerWidth * 0.5f)
    val topology = LocalRouteTopology.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // The card is a traversal group; each interior route row is its own sub-group (below) so its
        // ⚠ precedes its own countdown, not the next row's (SPEC principle 2).
        Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
            var firstRow = true
            group.rows.forEach { row ->
                val stale = Staleness.isStale(Duration.between(row.fetchedAt, now).toKotlinDuration())
                val isStarred = StarredRow.of(row) in starred
                if (row.upcoming.isEmpty()) {
                    // A status row: the line is suspended (its reason shown) and returned no
                    // predictions (SPEC *Departures*). Tappable to the detail view, but not starrable
                    // — a no-departures row has nothing to rank.
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    RouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = false,
                        onToggleStar = onToggleStar,
                        onOpenDetail = onOpenDetail,
                        onHideMode = onHideMode,
                    ) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                        // The reason chip lives in the weighted slack so it absorbs the shrink (and
                        // ellipsizes) when space is tight; the status text is unweighted, so the Row
                        // reserves its width — the status can't be squeezed to zero.
                        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            row.status?.let { status -> DisruptionChip(status.description) }
                        }
                        // A dash when the line's source answered with no trains; "No data" when no
                        // source did; "No key" (a tap away in Settings) for a National Rail line a
                        // key would give times.
                        val noTimes = NoTimes.of(row)
                        val noKey = noTimes == NoTimes.NO_KEY
                        val noTrains = stringResource(R.string.status_no_departures_description)
                        Text(
                            text = stringResource(
                                when (noTimes) {
                                    NoTimes.NO_TRAINS -> R.string.status_no_departures
                                    NoTimes.NO_KEY -> R.string.status_no_rail_key
                                    NoTimes.NO_DATA -> R.string.status_no_data
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (noKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .then(
                                    // The dash alone could be heard as missing data; say "No departures".
                                    if (noTimes == NoTimes.NO_TRAINS) {
                                        Modifier.semantics { contentDescription = noTrains }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (noKey) {
                                        Modifier.clickable(
                                            onClickLabel = stringResource(R.string.status_no_rail_key_action),
                                            role = Role.Button,
                                            onClick = onOpenSettings,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    return@forEach
                }
                // Timed rows: grouped by destination *and branch* (the shared `destinationLines`, so
                // the widget can't drift), each destination its own route row with its own merged
                // countdown — a countdown is never read under the wrong destination or branch (SPEC
                // D8). A branching row (Northern to Morden and to Battersea) shows its pill on each.
                val starrable = starringAvailable && row.stopDisruption == null && row.upcoming.isNotEmpty()
                val destinationLines = DepartureRows.destinationLines(row, MAX_TIMES, topology)
                destinationLines.forEach { group2 ->
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    // The destination, or the direction key as a cue when TfL gives no destination, so
                    // cards TfL keeps distinct stay distinguishable (SPEC principle 1).
                    val label = DepartureLabels.destinationLabel(group2.destination, row.directionKey)
                        ?: stringResource(R.string.destination_unknown)
                    RouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = starrable,
                        onToggleStar = onToggleStar,
                        onOpenDetail = onOpenDetail,
                        // The route row's own soonest train names the route the detail follows.
                        focus = RouteFocus.of(group2),
                        onHideMode = onHideMode,
                    ) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                        DestinationLabelContent(
                            label = label,
                            branch = group2.branch,
                            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp),
                        )
                        // A disrupted line shows an inline ⚠ just left of the countdown, announced
                        // first (traversalIndex, in DisruptionWarningGlyph) so the warning precedes the
                        // countdown it qualifies (SPEC D3 / principle 2). It replaces the old full-width
                        // chip; the full status text stays reachable in the detail view.
                        row.status?.let { status ->
                            DisruptionWarningGlyph(status.description, Modifier.padding(end = 8.dp))
                        }
                        CountdownLabel(group2.times, stale, now)
                    }
                }
            }
        }
    }
}

/** A thin divider between the interior route rows of a group card, in the outline color. */
@Composable
private fun RouteDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One interior route row of a group card: a [Row] whose [content] is the pill, destination, optional
 * ⚠, and countdown. A TAP opens the detail view; a LONG-PRESS pins the route when [starrable]
 * (starring available, not a stop closure, and it has upcoming departures). A [isStarred] route wears
 * a 3dp gold leading-edge bar — all destination rows of one starred [DepartureRow] show it, since
 * starring is keyed per line+direction ([StarredRow.of]).
 *
 * A `pointerInput` gesture plus a **non-merging** `semantics` block (NOT `combinedClickable`, which
 * flattens the descendant traversal so the ⚠ could no longer precede its countdown): the block adds
 * the labeled tap/long-press and marks the row its own traversal sub-group. Callbacks are read via
 * `rememberUpdatedState` and the gesture is keyed on the stable [starrable] so the 10s clock's
 * recomposition never drops an in-progress long-press (Codex).
 */
@Composable
private fun RouteRow(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    onToggleStar: (DepartureRow) -> Unit,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    // The route this row shows (null for a status row), handed to the detail so it opens on it.
    focus: RouteFocus? = null,
    // Makes a long press open a menu — the pin/unpin, then "Hide ‹mode›" for this row's mode (SPEC
    // *Finding stops → Hiding a mode*) — instead of pinning at once. Null keeps the direct pin.
    onHideMode: ((String) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val starActionLabel = stringResource(if (isStarred) R.string.unstar else R.string.star)
    val detailActionLabel = stringResource(R.string.departure_details)
    val moreLabel = stringResource(R.string.more_actions)
    val currentRow by rememberUpdatedState(row)
    val currentToggleStar by rememberUpdatedState(onToggleStar)
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentFocus by rememberUpdatedState(focus)
    val hideMode = row.mode.takeIf { onHideMode != null && it.isNotBlank() }
    var menuOpen by remember { mutableStateOf(false) }
    val onLongPress: ((Offset) -> Unit)? = when {
        hideMode != null -> { _ -> menuOpen = true }
        starrable -> { _ -> currentToggleStar(currentRow) }
        else -> null
    }
    val starColor = LocalStarredBorderColor.current
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(starrable, hideMode) {
                detectTapGestures(
                    onTap = { currentOpenDetail(currentRow, currentFocus) },
                    onLongPress = onLongPress,
                )
            }
            .semantics {
                isTraversalGroup = true
                onClick(label = detailActionLabel) { currentOpenDetail(currentRow, currentFocus); true }
                when {
                    hideMode != null -> onLongClick(label = moreLabel) { menuOpen = true; true }
                    starrable -> onLongClick(label = starActionLabel) { currentToggleStar(currentRow); true }
                }
            }
            .then(
                if (isStarred) {
                    Modifier.drawBehind {
                        drawRect(color = starColor, size = size.copy(width = 3.dp.toPx()))
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    if (hideMode != null) {
        HideModeMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            modes = listOf(hideMode),
            onHideMode = { mode -> onHideMode?.invoke(mode) },
            leading = if (starrable) {
                {
                    DropdownMenuItem(
                        text = { Text(starActionLabel) },
                        onClick = {
                            menuOpen = false
                            currentToggleStar(currentRow)
                        },
                    )
                }
            } else {
                null
            },
        )
    }
    }
}

/**
 * The inline disruption warning ⚠ on a route row (SPEC D3): a small glyph in the error color, sat
 * just left of the countdown but announced first ([traversalIndex] = -1f) so a screen reader voices
 * the warning — TfL's full status wording, its [description] — before the countdown it qualifies
 * (SPEC principle 2). A glyph `Text`, not `Icons.Filled.Warning`, since that isn't in
 * material-icons-core (like the outline star the app already vendors).
 */
@Composable
private fun DisruptionWarningGlyph(description: String, modifier: Modifier = Modifier) {
    Text(
        text = "⚠",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics {
            contentDescription = description
            traversalIndex = -1f
        },
    )
}

/**
 * A stop closure (a stop-level disruption) as the card's content: the disruption in an
 * error-toned surface under the place's own [title] (the interchange name, else the stop). The
 * stop's own departures, if any, show in their own cards below.
 *
 * **The title heads the card always; the body collapses to one line and expands on tap.** Not
 * every notice names its own stop — a bus "Bus Stop Closed" never does — so the heading is what
 * says *which* stop even when collapsed (SPEC *Disruptions*); a tube notice's own leading repeat of
 * the name is stripped upstream ([cleanDisruptionBody]) so the heading isn't said twice. TfL's
 * notices are prose (a paragraph on a lift outage), and a glance surface shouldn't be dominated by
 * one, so collapsed the [disruption] body is its first line alone and tapping expands it to the full
 * text (SPEC *Concise copy* / jank-free UI). The body `Text` exposes its full string to the
 * accessibility tree regardless of the visual clip, so a screen reader reads the whole notice
 * whether or not it is expanded; the tap only changes what is drawn. Expanded state is
 * `rememberSaveable`, keyed on the notice text, so it survives a configuration change and never
 * bleeds onto a different notice when a `LazyColumn` row is recycled.
 */
@Composable
private fun StopClosureContent(disruption: String, title: String, onDismiss: () -> Unit = {}) {
    CollapsibleStatus(text = disruption, title = title, onDismiss = onDismiss)
}

/**
 * The shared "first line, tap to expand" disruption surface (SPEC *Disruptions* / *Concise
 * copy*): an error-toned rounded box that collapsed shows [text]'s first line alone and
 * expanded shows it in full, with a chevron marking it expandable. When a [title] is given it
 * heads the surface, collapsed and expanded. Used for the stop-closure card
 * ([StopClosureContent], where [title] is the interchange/stop name) and for the route detail's
 * line disruption ([RouteDetailScreen], where [title] is null — the app bar already carries the
 * line and destination).
 *
 * The `text` `Text` exposes its full string to the accessibility tree regardless of the visual
 * clip, so a screen reader reads the whole notice whether or not it is expanded; the tap only
 * changes what is drawn. Expanded state is `rememberSaveable`, keyed on [text], so it survives a
 * configuration change and never bleeds onto a different notice when a `LazyColumn` row is
 * recycled.
 *
 * When [onDismiss] is non-null a leading dismiss (×) button hides the alert (the stop-closure card
 * — the user's read-and-clear control, SPEC *Disruptions*); the route detail passes null, since its
 * line alert's dismiss sits beside the status chip instead. The button has its own click target, so a
 * dismiss tap doesn't also toggle the expand/collapse.
 */
/**
 * [text] with each web link ([AlertLinks]) made a tappable, underlined link. Opening goes through
 * Compose's [LinkAnnotation.Url], which hands the URL to the platform's browser — only `http`/`https`
 * links are produced, so a tap can't open any other scheme. A link is also an accessibility action,
 * so a screen reader can open it.
 */
private fun linkified(text: String, onOpen: LinkInteractionListener): AnnotatedString {
    val links = AlertLinks.find(text)
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (link in links) {
            append(text, at, link.range.first)
            withLink(LinkAnnotation.Url(link.url, style, onOpen)) { append(text.substring(link.range)) }
            at = link.range.last + 1
        }
        append(text, at, text.length)
    }
}

@Composable
private fun CollapsibleStatus(
    text: String,
    title: String?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val clickLabel = stringResource(if (expanded) R.string.alert_collapse else R.string.alert_expand)
    Surface(
        color = container,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel) { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The title (the interchange/stop name) heads the surface whenever one is given —
                // collapsed and expanded — so a notice that doesn't name its own stop still says
                // which stop. The route detail passes null (its dialog header already carries the
                // line and place), so nothing is shown there.
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Web links in the notice (TfL often names a page for more detail) are underlined
                // and open in the browser on tap; the rest of the card still toggles expand/collapse.
                // Opened through a guarded listener rather than the default URI handler, which throws
                // on a device with no browser: say so instead of crashing (as LicensesScreen does).
                val context = LocalContext.current
                val linkFailed = stringResource(R.string.link_open_failed)
                val onOpenLink = remember(context, linkFailed) {
                    LinkInteractionListener { annotation ->
                        val url = (annotation as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            // The URL is TfL's own alert text, not user data; the log still omits it.
                            Log.w("Alerts", "No activity to open an alert link", e)
                            Toast.makeText(context, linkFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val linked = remember(text, onOpenLink) { linkified(text, onOpenLink) }
                Text(
                    text = linked,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (title != null) Modifier.padding(top = 4.dp) else Modifier,
                )
            }
            // A quiet chevron marks the row as expandable; the click label carries the action
            // for a screen reader, so the icon itself needs no separate description.
            val chevron = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
            if (onDismiss != null) {
                // On a dismissible closure card the chevron and the × are BOTH centered in a 48dp
                // band, so their glyphs line up on one axis, with a clear gap between them — they read
                // as two separate controls instead of a cramped, misaligned cluster.
                Box(
                    modifier = Modifier.padding(start = 8.dp).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(chevron, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                // The dismiss (×) keeps its default 48dp interactive target, flush against the chevron
                // band so there is no dead gap between them: the visual separation from the chevron is
                // the button's own internal padding, which is part of the dismiss hit target — a near
                // miss just left of the glyph still dismisses rather than toggling expand/collapse
                // (SPEC *Disruptions*). No outer padding, which would sit outside the clickable and
                // fall through to the card.
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // A status card without its own × (the route detail's line prose) keeps the top-aligned
                // chevron beside its text — no × to align with.
                Icon(chevron, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }
}

/** The stable identity of the route a [DepartureRow] represents — its stop, line, and direction —
 *  used as the saveable key for the open route-detail page so it re-resolves against live rows. */
private fun DepartureRow.detailKey(): String = "$stopId|$lineId|$directionKey|$platform"

/**
 * The tap-to-open route detail (SPEC D8 / *Disruptions*, `TODO.md`): a full-screen page — reached by
 * a tap on a timed or line-status card (a stop-closure card expands in place, so it never reaches
 * here) — that replaces the departures screen with its own app bar. The bar names the route (line
 * pill + destination) and carries the star (the list card only long-presses to pin); the body names
 * the boarding stop and the line's full disruption text the compact chip stands in for.
 *
 * A full screen rather than a dialog (maintainer, 2026-09-22): it will grow the maps/nav hand-off
 * and other per-route actions (`TODO.md`), which a dialog would cap. Mirrors the app's other full
 * screens ([LicensesScreen], [SettingsScreen]) — a composable with an [onBack], switched in by the
 * host's screen state; [BackHandler] routes the system back to [onBack] too, and it inherits the
 * theme's scaled density from the host, so no per-window font host is needed here.
 *
 * Pure: renders only [row] and reports the two actions. The star shows only when [starrable] (a
 * timed row with starring available) — a no-departures status row has nothing to rank, matching the
 * list's own rule — while the disruption text shows whenever the line carries prose, so a status
 * row still opens to its full alert.
 */
@Composable
internal fun RouteDetailScreen(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    // True when THIS row's disruption state is unchecked (its line was blank-id or omitted by TfL,
    // the line-status lookup failed, or its stop's own disruption lookup failed) rather than
    // checked-clean: the detail then says "couldn't check" rather than a verified-clean "no
    // disruptions" (SPEC principle 1). The caller decides this per row, so one unknown line or stop
    // doesn't taint a checked-clean row.
    disruptionUnknown: Boolean,
    // True when this row's snapshot has crossed the staleness threshold: the status is from an old
    // fetch, so the page — which carries no freshness stamp of its own, unlike the list — caveats it
    // and never claims "no disruptions" from stale data (SPEC D4).
    stale: Boolean,
    // The ticking clock the countdowns count down against, as on the list.
    now: Instant,
    onToggleStar: () -> Unit,
    onBack: () -> Unit,
    // The stop list for the soonest train. Null resolves it from [LocalRouteStops]; a screenshot
    // test passes a fixed state.
    routeStops: RouteStopsUi? = null,
    // The route tapped on the card; null (a status row, or a caller with no route) follows the
    // row's soonest train.
    focus: RouteFocus? = null,
    // Dismisses the line's status alert (SPEC *Disruptions*): hidden until TfL changes its severity
    // or wording. Null shows no dismiss control.
    onDismissAlert: (() -> Unit)? = null,
    // The starred journeys (SPEC *Journeys*): a station on the stop list with one from this stop is
    // starred, and tapping a station stars or unstars the journey there. Null (a bus, whose return
    // leaves from another pole, or a caller without journeys) leaves the stations inert.
    journeys: List<StarredJourney> = emptyList(),
    onToggleJourney: ((StarredJourney) -> Unit)? = null,
    // Dismisses the tip on starring a journey from the stop list; null shows none.
    onDismissJourneyTip: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val followed = followedDeparture(row, focus, LocalRouteTopology.current)
    var routeStopsRetry by rememberSaveable { mutableIntStateOf(0) }
    // A stale row's soonest prediction may not be the next train any more, so its stop list is
    // withheld rather than shown as current (SPEC D4); it returns as soon as a refresh lands.
    val stops = when {
        // Only a row with a train to follow: a status row has no list to withhold.
        stale && row.upcoming.isNotEmpty() && row.lineId.isNotBlank() -> RouteStopsUi.Stale
        routeStops != null -> routeStops
        else -> rememberRouteStops(row, followed, routeStopsRetry)
    }
    // Every upcoming train on the followed route, not the card's first few — TfL predicts ~30 min
    // ahead, and the page has the room (SPEC *Route detail*).
    val topology = LocalRouteTopology.current
    val departures = remember(row, focus, topology) { routeDepartures(row, focus, topology) }
        .filterNot { Countdown.hasDeparted(it, now) }
    // The terminus(es) this service runs to, from its own departures — empty for a status row
    // (no predictions), which then shows only the line and its disruption.
    val destinations = if (row.upcoming.isEmpty()) {
        emptyList()
    } else if (focus != null && followed != null) {
        // A tapped route names just that route, matching the stop list below it.
        listOfNotNull(DepartureLabels.destinationLabel(followed.destination, row.directionKey))
    } else {
        DepartureRows.destinationLines(row, MAX_TIMES, LocalRouteTopology.current)
            .mapNotNull { DepartureLabels.destinationLabel(it.destination, row.directionKey) }
            .distinct()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                // Name the route being viewed — the line pill, then where it's going — so the bar
                // reflects the page's subject (maintainer, 2026-09-22). A status row with no
                // predictions shows just the pill.
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode)
                        if (destinations.isNotEmpty()) {
                            Text(
                                text = destinations.joinToString(", "),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                },
                actions = {
                    // The star (pin-to-top) as the bar's action — the discoverable control, shown
                    // only where the row is pinnable (same rule the list card uses). Filled and gold
                    // when starred, matching the list card's gold pin border; the vendored outline
                    // star when not (material-icons-core ships the filled Star but not its outline).
                    // No visible label — the contentDescription flips with state so a screen reader
                    // still hears what the tap does (SPEC principle 2).
                    if (starrable) {
                        IconButton(onClick = onToggleStar) {
                            Icon(
                                imageVector = if (isStarred) Icons.Filled.Star else StarBorderIcon,
                                contentDescription = stringResource(
                                    if (isStarred) R.string.unstar else R.string.star,
                                ),
                                tint = if (isStarred) {
                                    LocalStarredBorderColor.current
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // Scrollable so a long expanded alert or a large text size isn't clipped (SPEC *Display
        // size*); the 16dp gutter matches the app's other reading surfaces.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // "From Victoria" names the boarding stop only while no stop list does: the list opens on
            // it, so with the list shown the label is repeated noise, but a status row (no train to
            // follow) or a loading, failed, or withheld list would otherwise leave the page not saying
            // which stop it's about — ambiguous when one line is watched at two stops (Codex).
            val place = row.hubName.ifBlank { row.stopName }
            val showFrom = stops !is RouteStopsUi.Loaded && place.isNotBlank()
            if (showFrom) {
                Text(
                    text = stringResource(R.string.route_detail_from, place),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The followed route's countdowns, leading the page — the card's one-line format, times
            // only, but across the page's full width so more fit; the ones that don't ellipsize off
            // the end, keeping the soonest. Left out while stale — the stale caveat below says why —
            // so an old prediction is never shown as live (SPEC D4).
            if (departures.isNotEmpty() && !stale) {
                Text(
                    text = Countdown.mergedLabel(departures, now),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = if (showFrom) 8.dp else 0.dp),
                )
            }
            val status = row.status
            if (status != null) {
                // The short chip label always; the full prose below it, collapsed to its first line
                // with tap-to-expand, when TfL gave a reason (SPEC *Disruptions*). The dismiss (×) sits
                // at the chip's end, so it's there whether or not TfL gave prose.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) { DisruptionChip(status.description) }
                    if (onDismissAlert != null) {
                        IconButton(onClick = onDismissAlert) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.alert_dismiss),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                status.fullText?.let { fullText ->
                    CollapsibleStatus(
                        text = fullText,
                        title = null,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // The disruption-check state, independent of staleness (the two caveats are separate
            // facts, both shown when both apply — Codex): "couldn't check" whenever this row's line
            // was undetermined OR its stop's disruption lookup failed — a known line alert doesn't
            // mean the stop-level closure/move check ran, so the note sits alongside the alert too,
            // and the stale caveat below never stands in for it. "No disruptions reported" only when
            // determined-clean AND fresh — never claimed from stale or unchecked data (SPEC principle
            // 1 / D4). A stale but determined-clean row shows neither here; the stale caveat covers it.
            // A dismissed line alert is its own fact, shown beside any "couldn't check" note: the line
            // IS disrupted, the user only hid the alert, so never claim a clean line (SPEC principle 1).
            if (row.statusDismissed) {
                Text(
                    text = stringResource(R.string.route_detail_alert_dismissed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (disruptionUnknown) {
                Text(
                    text = stringResource(R.string.disruptions_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (status == null && !row.statusDismissed && !stale) {
                Text(
                    text = stringResource(R.string.route_detail_no_disruption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // The page has no freshness stamp of its own, so a stale snapshot is caveated here — an
            // independent fact from the disruption-check state above: it sits under a shown
            // disruption (which may be resolved or superseded), replaces a "no disruptions" claim
            // (a new one may exist), and sits alongside a "couldn't check" note (age and a failed
            // check are different gaps) alike (SPEC D4).
            if (stale) {
                Text(
                    text = stringResource(R.string.route_detail_status_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // Each saved journey that boards here on this page, with the stops on this page it ends at:
            // by id, or placed on this page's route the way the journey card places it (a bus's way
            // back boards across the road).
            // The row's mode, from any of its departures when TfL left it off the soonest one.
            val rowMode = row.mode.ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
            val pageSequence = (stops as? RouteStopsUi.Loaded)?.sequence
            val pageStopIds = (stops as? RouteStopsUi.Loaded)?.stops?.mapTo(HashSet()) { it.id }.orEmpty()
            val journeysHere = remember(journeys, pageSequence, pageStopIds, row.stopId, row.lineId) {
                journeys.mapNotNull { j ->
                    // The saved ids only when the other end is itself on this page's list; a bus's
                    // way back may board at a saved pole but alight across the road.
                    val byId = when (row.stopId) {
                        j.from.stopId -> setOf(j.to.stopId)
                        j.to.stopId -> setOf(j.from.stopId)
                        else -> null
                    }?.takeIf { pageSequence == null || it.any { id -> id in pageStopIds } }
                    val placed = byId ?: pageSequence?.let { seq ->
                        listOf(j, j.reversed()).firstNotNullOfOrNull { cand ->
                            Journeys.segment(cand, seq, row.lineId)?.takeIf { it.originId == row.stopId }?.destinationIds
                        }
                    }
                    placed?.let { j to it }
                }.toMap()
            }
            // Every station from here to where the soonest train terminates (SPEC *Route detail*).
            RouteStopsSection(
                state = stops,
                railColor = railColorFor(row),
                onRetry = { routeStopsRetry++ },
                modifier = Modifier.padding(top = 16.dp),
                // Rail reads it off the followed train's platform ("Southbound - Platform 2"); a bus
                // off its pole's compass bearing. Neither known, no heading rather than a guess.
                direction = PlatformDirection.of(followed?.platform) ?: bearingDirection(row.bearing),
                // By segment, whatever line or direction it was starred from: the 43 and the 134
                // between two shared stops are one journey, and so is its way back from the poles
                // across the road — so any of those pages shows (and toggles) the same star.
                starredStopIds = journeysHere.values.flatMapTo(mutableSetOf()) { it },
                onDismissJourneyTip = onDismissJourneyTip,
                onToggleJourneyTo = onToggleJourney
                    ?.takeIf { row.lineId.isNotBlank() && (Connections.isRail(rowMode, row.lineId) || rowMode.equals("bus", ignoreCase = true)) }
                    ?.let { toggle ->
                        { stop ->
                            val positions = (stops as? RouteStopsUi.Loaded)?.positions.orEmpty()
                            val areas = (stops as? RouteStopsUi.Loaded)?.sequence?.stopAreas.orEmpty()
                            // The name as the list shows it: a stop TfL gave no name keeps its id,
                            // so a saved journey's heading never has a blank end. Its stop area rides
                            // along, so "Find a station" can open the end as the whole place.
                            fun end(id: String, name: String) =
                                JourneyEnd(id, name.ifBlank { id }, positions[id]?.first, positions[id]?.second, areas[id].orEmpty())
                            // A saved journey this stop already ends on this page is toggled (off) as
                            // itself, rather than starred again under this direction's pole ids.
                            val existing = journeysHere.entries.firstOrNull { stop.id in it.value }?.key
                            toggle(
                                existing ?: StarredJourney(
                                    end(row.stopId, row.stopName), end(stop.id, stop.name), row.lineId, row.lineName, rowMode,
                                ),
                            )
                        }
                    },
            )
        }
    }
}

/**
 * One destination line within a card — `destination · · · countdown` — used for the
 * soonest destination and for each divergent one of a branching direction alike, so they
 * render at the **same weight and the same indentation** (all sit in the card's one
 * destination column, beside the pill). [times] empty is a status row: the line is named
 * with no countdown. A long destination first shortens common whole words
 * ([DestinationAbbreviations]) and then, if it still doesn't fit, is hard-truncated with a
 * clean cut — no ellipsis (maintainer preference) — rather than crowding out the countdown.
 * The whole destination line — terminus and its branch/via — clips; only the countdown
 * keeps its ellipsis (below).
 */
@Composable
internal fun DestinationLine(
    label: String,
    times: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
    // The "via" branch (TfL's `towards`), joined to the destination with a slash —
    // "Battersea/Charing X" — the cue a rider uses to pick a train. Null for most
    // services; a branch-free row whose name has no abbreviatable word takes the cheap
    // no-measuring path below.
    branch: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DestinationLabelContent(label, branch, Modifier.weight(1f).padding(end = 12.dp))
        if (times.isNotEmpty()) {
            CountdownLabel(times, stale, now)
        }
    }
}

/**
 * The destination label of a route — the terminus, its abbreviation/branch rendering — laid into the
 * [modifier] the caller provides (a weighted slot beside the countdown, or beside the pill on a group
 * card). Extracted from [DestinationLine] so both the standalone destination line and the group
 * card's route rows render the label identically. A branch-free name shortens common whole words
 * ([DestinationAbbreviations]) then hard-clips (no ellipsis); a branching name shares the width with
 * its "/branch" cue ([branchedLabel]), the branch outranking the terminus. The full name stays the
 * screen-reader label when the visible text is shortened.
 */
@Composable
private fun RowScope.DestinationLabelContent(label: String, branch: String?, modifier: Modifier) {
    if (branch == null) {
        val style = MaterialTheme.typography.titleMedium
        val abbreviated = remember(label) { DestinationAbbreviations.abbreviate(label) }
        val floor = remember(label) { DestinationAbbreviations.floor(label) }
        if (abbreviated == label && floor == label) {
            // Nothing to shorten (a one-word name, no mapped words): show it, and elide with a single
            // "…" (never a mid-glyph cut) only if the countdown leaves too little room.
            Text(
                text = label,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        } else {
            // Measure the ladder and show the longest form that fits — full, then word-abbreviated
            // ("East Finchley" → "E. Finchley"), then the floor ("Battersea Power" → "Battersea P.") —
            // eliding with a single "…" only below the floor (SPEC destination-label — shorten before
            // eliding, and never cut mid-glyph).
            BoxWithConstraints(modifier = modifier) {
                val measurer = rememberTextMeasurer()
                // Key each measurement on the font scale, not the text alone: a display-size /
                // accessibility resize grows the text while the row's px width is unchanged, so a
                // width cached on the string would stay stale and the shrink never fire.
                val fontScale = LocalDensity.current.fontScale
                fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
                val fullWidth = remember(label, style, fontScale) { widthOf(label) }
                val abbrevWidth = remember(abbreviated, style, fontScale) { widthOf(abbreviated) }
                val max = constraints.maxWidth
                val display = when {
                    fullWidth <= max -> label
                    abbrevWidth <= max -> abbreviated
                    else -> floor
                }
                Text(
                    text = display,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Keep the full name for a screen reader when the visible text is shortened.
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (display != label) Modifier.semantics { contentDescription = label }
                            else Modifier,
                        ),
                )
            }
        }
    } else {
        // The branch is the cue that tells a branching line's two trunks apart, so it is kept whole:
        // the branch lays out at its natural width and the terminus takes the leftover, shrinking
        // (word-abbreviated, then floored) and eliding with a single "…" before it would cut — never
        // mid-glyph (SPEC destination-label). The branch's own label is already the board short form
        // ("Charing X"), so [abbreviateBranch] is a no-op here; a fuller rider-readable form is a
        // follow-up (TODO). branchedLabel turns the measured widths into the strings, so the rule is
        // unit-tested apart from the render.
        BoxWithConstraints(modifier = modifier) {
            val style = MaterialTheme.typography.titleMedium
            val measurer = rememberTextMeasurer()
            val abbreviatedLabel = remember(label) { DestinationAbbreviations.abbreviate(label) }
            val floorLabel = remember(label) { DestinationAbbreviations.floor(label) }
            val shortBranch = remember(branch) { abbreviateBranch(branch) }
            // Key each measurement on the font scale as well as the text, so a display-size /
            // accessibility resize re-measures rather than reusing a width cached on the string alone.
            val fontScale = LocalDensity.current.fontScale
            fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
            val resolved = branchedLabel(
                label = label,
                abbreviatedLabel = abbreviatedLabel,
                floorLabel = floorLabel,
                branch = branch,
                abbreviatedBranch = shortBranch,
                maxWidth = constraints.maxWidth,
                labelWidth = remember(label, style, fontScale) { widthOf(label) },
                abbrevLabelWidth = remember(abbreviatedLabel, style, fontScale) { widthOf(abbreviatedLabel) },
                floorLabelWidth = remember(floorLabel, style, fontScale) { widthOf(floorLabel) },
                fullBranchWidth = remember(branch, style, fontScale) { widthOf("/$branch") },
                abbrevBranchWidth = remember(shortBranch, style, fontScale) { widthOf("/$shortBranch") },
                minStubWidth = remember(floorLabel, style, fontScale) { widthOf("${floorLabel.take(1)}…") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (resolved.terminus.isNotEmpty()) {
                    Text(
                        text = resolved.terminus,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        // Ellipsize cleanly (single "…", never mid-glyph) in the room the whole branch
                        // leaves; branchedLabel already shrank the terminus to a form that fits.
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // Keep the full name for a screen reader when the visible text is shortened.
                            .then(
                                resolved.contentDescription?.let { full ->
                                    Modifier.semantics { contentDescription = full }
                                } ?: Modifier,
                            ),
                    )
                }
                Text(
                    text = resolved.branch,
                    style = style,
                    maxLines = 1,
                    // Kept whole at its natural width; ellipsis is a last resort only for a long
                    // branch standing alone in the narrowest row (nothing left to yield).
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // When the branch stands alone (no terminus shown), its contentDescription carries
                    // BOTH the full terminus and the branch, so a screen reader can still tell two
                    // otherwise-identical Bank and Charing Cross rows apart (Codex P2).
                    modifier = if (resolved.terminus.isEmpty()) {
                        resolved.contentDescription?.let { full ->
                            Modifier.semantics { contentDescription = "$full via ${resolved.branch}" }
                        } ?: Modifier
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * A service's merged countdown — "0 · 3 · 6 min" (SPEC D8, one line per destination).
 * Withheld as "—" once the stop is stale, since the underlying predictions are likely
 * wrong and a live-looking number would misrepresent them (SPEC D4).
 */
@Composable
private fun CountdownLabel(
    departures: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (stale) WITHHELD else Countdown.mergedLabel(departures, now),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        // One line, never wrapped — the countdown is the one thing that must stay legible
        // (SPEC D8). It's the unweighted element of its row, so it's measured first and its
        // width reserved; the destination beside it ellipsizes when space is tight. In the
        // extreme (a long pill + the full "0 · 3 · 6 min" at a large font, on a narrow
        // screen) even the whole line can be too short: ellipsize from the end rather than
        // hard-clip, so the soonest times — which lead the label — stay legible and the
        // truncation reads as one ("0 · 3 …").
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color =
            if (stale) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * Marks a row whose line is disrupted (SPEC D3). A small error-toned chip carrying TfL's
 * status wording, sat between the header and the destination so it reads before the
 * countdowns it qualifies. The pill above already names the line, so the chip is the
 * status alone ("Severe Delays"), not "Victoria line: severe delays".
 */
@Composable
private fun DisruptionChip(description: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/**
 * The app's name in the top bar, drawn only when it fits whole. The bar's actions (the freshness
 * stamp, refresh, the menu) come first, and at a large text size they can leave the title a sliver:
 * wrapped it stacked one letter per line, and ellipsized it was a bare "…". The mark beside it already
 * says which app this is, so the name is left out rather than cut; a screen reader still hears it.
 */
@Composable
private fun AppTitle() {
    var fits by remember { mutableStateOf(true) }
    Text(
        stringResource(R.string.app_name),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { fits = !it.hasVisualOverflow },
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
    )
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onRefresh, modifier = modifier) {
        Text(stringResource(R.string.refresh))
    }
}

internal fun errorMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.error_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.error_rate_limited
    DeparturesUiState.Error.Kind.UNREACHABLE -> R.string.error_unreachable
}

private fun refreshFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.refresh_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.refresh_failed_rate_limited
    DeparturesUiState.Error.Kind.UNREACHABLE -> R.string.refresh_failed_unreachable
}

private const val MAX_TIMES = 3
// A stale stop's countdown is unknown, not zero, so it withholds the number as "?" — "—"
// read as "none," which is a different thing (that's the no-departures status). The stamp
// up top ("Tap to refresh") says why.
private const val WITHHELD = "?"

/**
 * The scroll positions of the drill-down views open from the full list (a station, a platform in
 * it, a journey), one per view identity, saved across rotation. Held in a plain map — it is read
 * only to hand a view its [LazyListState], never observed — and cleared when the full list is back.
 */
internal class DrillScrollStates(private val states: HashMap<String, LazyListState> = HashMap()) {
    fun stateFor(key: String): LazyListState = states.getOrPut(key) { LazyListState() }

    fun clear() = states.clear()

    companion object {
        val Saver: Saver<DrillScrollStates, Any> = listSaver(
            save = { holder ->
                holder.states.flatMap { (key, state) ->
                    listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            },
            restore = { saved ->
                DrillScrollStates(
                    saved.chunked(3).associateTo(HashMap()) { (key, index, offset) ->
                        key as String to LazyListState(index as Int, offset as Int)
                    },
                )
            },
        )
    }
}

/**
 * One [StopRef] per journey origin stop: the lines of every journey fetching it, and the first
 * interchange any of them knows, so one whose route isn't in yet doesn't blank another's.
 */
internal fun mergeJourneyOrigins(refs: List<StopRef>): List<StopRef> =
    refs.groupBy { it.id }.map { (_, same) ->
        same.first().copy(
            lines = same.flatMap { it.lines }.distinctBy { it.id },
            hubId = same.firstOrNull { it.hubId.isNotBlank() }?.hubId.orEmpty(),
        )
    }
