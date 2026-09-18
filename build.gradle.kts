plugins {
    alias(libs.plugins.android.application) apply false
    // Never applied, but its presence pins the Kotlin Gradle Plugin AGP's
    // built-in Kotlin compiles with, so the Compose compiler plugin (pinned to
    // the same `kotlin` version) can't drift from the baseline AGP would pick.
    alias(libs.plugins.kotlin.jvm) apply false
    // Applied by :app. Declared here so their version rides `kotlin` in the
    // catalog: the Compose and serialization compiler plugins must match the
    // Kotlin version exactly.
    alias(libs.plugins.kotlin.compose) apply false
}
