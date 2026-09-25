package app.stopdash

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PlayUpdateChecker] against Play's own [FakeAppUpdateManager] — no real Play, no network.
 * The success/failure callbacks land on the main looper, so each check is flushed with
 * `shadowOf(Looper.getMainLooper()).idle()` before asserting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayUpdateCheckerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun checker(fake: FakeAppUpdateManager, checksEnabled: Boolean = true) =
        PlayUpdateChecker(app, appUpdateManager = fake, checksEnabled = checksEnabled)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `reports available when Play has an update`() {
        val fake = FakeAppUpdateManager(app).apply { setUpdateAvailable(101) }
        var result: Boolean? = null
        checker(fake).checkForUpdate { result = it }
        idle()
        assertEquals(true, result)
    }

    @Test
    fun `reports unavailable when Play has no update`() {
        val fake = FakeAppUpdateManager(app) // default: no update available
        var result: Boolean? = null
        checker(fake).checkForUpdate { result = it }
        idle()
        assertEquals(false, result)
    }

    @Test
    fun `disabled checks report unavailable without asking Play`() {
        // Even with an update set, a debug build (checks disabled) reports false — synchronously,
        // never touching Play.
        val fake = FakeAppUpdateManager(app).apply { setUpdateAvailable(101) }
        var result: Boolean? = null
        checker(fake, checksEnabled = false).checkForUpdate { result = it }
        assertEquals(false, result)
    }

    @Test
    fun `an older check cannot clobber a newer answer`() {
        // Two checks in flight: the second must win. With a single fake resolving both on idle,
        // the generation guard drops the first result and keeps the second.
        val fake = FakeAppUpdateManager(app).apply { setUpdateAvailable(101) }
        val checker = checker(fake)
        val results = mutableListOf<Boolean>()
        checker.checkForUpdate { results += it }
        checker.checkForUpdate { results += it }
        idle()
        // Only the latest generation's callback applies; the stale one is ignored.
        assertEquals(listOf(true), results)
    }
}
