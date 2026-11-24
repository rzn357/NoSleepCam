plugins {
    alias(libs.plugins.android.application)
}

// -PstartupTrace=true enables startup timestamps and frame rate diagnostics in logcat
// (tags NSC-Startup, NSC-Fps, NSC-Req). In a normal build the calls are removed entirely by R8.
val startupTrace = providers.gradleProperty("startupTrace").map { it.toBoolean() }.orElse(false)

android {
    namespace = "com.exciteddev.nosleepcam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.exciteddev.nosleepcam"
        // Android 7.0: same as in the MAUI version. The Honor 10 is supported.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "STARTUP_TRACE", startupTrace.get().toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 optimization of the app's own code only. The template's packageScope = androidx/kotlin/kotlinx made R8
            // rewrite the stdlib too, after which release crashed at startup (IllegalAccessError: kotlin.collections.f).
            optimization {
                enable = true
            }
            // A personal app without its own key: we sign with the debug key so that the Release build
            // (the one on which startup speed must be measured) can be installed on the phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The app deliberately does not depend on AppCompat/Material/AndroidX: only framework widgets and the Kotlin stdlib —
// fewer classes to load on a cold start.
dependencies {
    testImplementation(libs.junit)
}
