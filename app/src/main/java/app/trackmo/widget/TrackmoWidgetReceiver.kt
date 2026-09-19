package app.trackmo.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The broadcast receiver the home-screen host binds to update the widget. Glance handles the
 * `APPWIDGET_UPDATE` plumbing; all this declares is which [GlanceAppWidget] backs it.
 */
class TrackmoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackmoWidget()
}
