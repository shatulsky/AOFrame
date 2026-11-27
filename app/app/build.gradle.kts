plugins {
    alias(libs.plugins.android.application)
    jacoco
}

android {
    namespace = "dev.aoframe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.aoframe"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Coverage per layer: unit tests write a .exec file, the
            // instrumented tests (integration and E2E) write a .ec file.
            // scripts/run-all-tests.sh merges them (see docs/testing.md).
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            // R8 + resource shrinking - smaller APK, faster install/cold
            // start on constrained hardware.
            optimization {
                enable = true
            }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-keystore signing, not a dedicated release key - a
            // sideload-only device typically has no Play Store, so a
            // separate release keystore buys nothing and would only
            // block `adb install -r` from upgrading in place (Android
            // requires a matching signature to keep app data across
            // installs).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.nanohttpd)
    // Lets ART install the hand-authored baseline profile below
    // (app/src/main/baselineProfiles/baseline-prof.txt) on first run -
    // API 27's partial-AOT model supports ProfileInstaller-driven install
    // even without Play/Cloud Profiles.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// One report from the three layers. Reads whatever coverage files exist,
// so it also works after a single layer. Run by scripts/run-all-tests.sh.
tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    description = "Merged line coverage of the unit, integration and E2E layers"
    val classes = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*")
    }
    classDirectories.setFrom(classes)
    sourceDirectories.setFrom(files("src/main/java"))
    // -Player=unit|integration|e2e reports one layer; no property merges all.
    val layer = providers.gradleProperty("layer").orNull
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir(if (layer == null) "layers" else "layers/$layer")) {
            include("**/*.exec", "**/*.ec")
        }
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
