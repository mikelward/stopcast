// The Wear OS companion app (dev-docs/wear-os.md): it renders the widget's snapshot, which the
// phone pushes over the Wearable Data Layer. It never calls TfL, holds no key and needs no location.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Data Layer only pairs apps with the same application ID (and signing key), so the watch
// app takes the phone's. It must not ship under the pre-rename ID; see the release gate below.
val watchApplicationId = "app.stopcast"

android {
    namespace = "app.stopcast.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = watchApplicationId
        // Wear OS 5 (Android 14), the fleet's API floor.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Matches the phone's debug suffix, so a debug phone build pairs with a debug watch build.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        ignoreTestSources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

// Release gate (maintainer, 2026-09-24; TODO Phase 6). The watch code is built ahead of the
// package rename, and a Play listing can't change its package, so nothing here may be released
// by accident: every release packaging task fails unless the build is run with
// -Pstopcast.wearRelease=approved, and fails anyway while the application ID is still the
// pre-rename one. CI never passes the flag, and asserts that :wear:bundleRelease fails without it.
// Lift it only in the PR that releases the watch app, after the rename and the launch decision.
val wearReleaseApproved = providers.gradleProperty("stopcast.wearRelease").orNull == "approved"
val releaseGate = tasks.register("checkWearReleaseGate") {
    description = "Fails a Wear OS release build until the maintainer lifts the release gate."
    val approved = wearReleaseApproved
    val id = watchApplicationId
    doLast {
        check(approved) {
            "The Wear OS app isn't released yet (TODO Phase 6): build it with " +
                "-Pstopcast.wearRelease=approved only once the maintainer has decided to launch."
        }
        check(id != "app.stopcast") {
            "The Wear OS app can't be released as $id: rename the package first (TODO Phase 6)."
        }
    }
}
// On the release variant's first task, so a release build stops before compiling anything.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(releaseGate) }

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
