package app.stopcast.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.stopcast.domain.RouteTopology

/**
 * The route topology available to the departures card, so it groups a branching row the same
 * way the widget does (see [app.stopcast.domain.DepartureRows.destinationLines]). Provided once
 * at the composition root ([app.stopcast.MainActivity]) from the bundled asset, warmed off the
 * render path. It defaults to [RouteTopology.EMPTY] so a screenshot test or preview that doesn't
 * provide it renders TfL's branches as-is (no merge) — static, so `staticCompositionLocalOf`.
 */
val LocalRouteTopology = staticCompositionLocalOf { RouteTopology.EMPTY }
