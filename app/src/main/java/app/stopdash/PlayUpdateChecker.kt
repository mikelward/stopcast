package app.stopdash

import android.app.Application
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks Google Play whether an app update is available, to drive the "update available" dot on
 * the overflow menu (SPEC *Update indicator*). Detection only: tapping the resulting menu item
 * opens the Play listing (see [MainActivity]), so this never runs the in-app update flow.
 *
 * Off in debug builds via [checksEnabled] ([BuildConfig.PLAY_UPDATE_CHECKS_ENABLED]) — their
 * `.debug` applicationId isn't a Play app, so a check there only ever fails. [appUpdateManager]
 * and [checksEnabled] are injectable so a JVM/Robolectric test drives a fake without Play.
 */
internal class PlayUpdateChecker(
    app: Application,
    // Coarse, PII-free warnings routed to the app's logger (SPEC *Privacy*); a failed Play
    // call carries no user data. Default no-op so an unwired build/test stays quiet.
    private val warn: (String) -> Unit = {},
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(app),
    private val checksEnabled: Boolean = BuildConfig.PLAY_UPDATE_CHECKS_ENABLED,
) {
    // Bumped per check so a slow earlier fetch can't clobber a newer answer's result.
    private var generation = 0

    /**
     * Asks Play whether an update is available. [onResult] runs on the main thread: `true` when
     * Play reports one, `false` otherwise. A failed fetch is inconclusive rather than "no
     * update", but a dot has nowhere to show "unknown", so it degrades to `false` (hide the dot)
     * and is logged. Cheap — a background Play `Task`, never on a render path.
     */
    fun checkForUpdate(onResult: (available: Boolean) -> Unit) {
        if (!checksEnabled) {
            onResult(false)
            return
        }
        val requested = ++generation
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (requested != generation) return@addOnSuccessListener
                onResult(info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE)
            }
            .addOnFailureListener { exception ->
                if (requested != generation) return@addOnFailureListener
                warn("Play update check failed: ${exception.javaClass.simpleName}")
                onResult(false)
            }
    }
}
