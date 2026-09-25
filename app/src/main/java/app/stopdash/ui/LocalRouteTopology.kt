package app.stopdash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.stopdash.domain.RouteTopology

/**
 * The route topology available to the departures card, so it groups a branching row the same
 * way the widget does (see [app.stopdash.domain.DepartureRows.destinationLines]). Provided once
 * at the composition root ([app.stopdash.MainActivity]) from the bundled asset, warmed off the
 * render path. It defaults to [RouteTopology.EMPTY] so a screenshot test or preview that doesn't
 * provide it renders TfL's branches as-is (no merge) — static, so `staticCompositionLocalOf`.
 */
val LocalRouteTopology = staticCompositionLocalOf { RouteTopology.EMPTY }
