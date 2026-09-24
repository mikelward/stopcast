// Android code the phone app and the Wear OS app (dev-docs/wear-os.md) share: the bundled route
// topology and its loader, so both group branching services the same way, and the line-pill
// color rules, so both color a service the same way. Pure product logic stays in :domain.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.stopcast.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
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

dependencies {
    api(project(":domain"))
    // Color is in the pill rules' public signatures.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.graphics)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
