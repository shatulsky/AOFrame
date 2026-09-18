plugins {
    alias(libs.plugins.android.application)
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
