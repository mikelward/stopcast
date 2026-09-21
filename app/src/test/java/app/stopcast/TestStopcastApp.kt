package app.stopcast

/**
 * The [android.app.Application] Robolectric instantiates for the unit-test suite (wired via
 * `src/test/resources/robolectric.properties`), in place of [StopcastApp].
 *
 * It skips [StopcastApp.installDiagnosticLog] so the suite does not, per test, stand up the
 * on-device file sink, spin up its writer thread, and chain a process-wide uncaught-exception
 * handler — none of which a screen or domain test needs. Tests exercise [StopcastDebugLog]
 * directly, and the library's own tests cover the file sink and crash handler.
 */
class TestStopcastApp : StopcastApp() {
    override fun installDiagnosticLog() {
        // Intentionally empty — see the class comment. Not a swallowed failure: there is no
        // work to do here, by design.
    }
}
