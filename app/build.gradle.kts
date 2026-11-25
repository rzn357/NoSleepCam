plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
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
        // Android 7.0 (the Honor 10 is supported).
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("boolean", "STARTUP_TRACE", startupTrace.get().toString())
    }

    buildFeatures {
        buildConfig = true
    }

    // The release key lives outside the repository; its location and passwords come from ~/.gradle/gradle.properties
    // (nosleepcam.signing.*). Without them (a fresh clone, CI) the release build is signed with the debug key instead,
    // so it still builds and installs — but such an APK is not a publishable release.
    val releaseKeyFile = providers.gradleProperty("nosleepcam.signing.storeFile").orNull
    signingConfigs {
        if (releaseKeyFile != null) {
            create("release") {
                storeFile = file(releaseKeyFile)
                storePassword = providers.gradleProperty("nosleepcam.signing.storePassword").get()
                keyAlias = providers.gradleProperty("nosleepcam.signing.keyAlias").get()
                keyPassword = providers.gradleProperty("nosleepcam.signing.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 optimization of the app's own code only. The template's packageScope = androidx/kotlin/kotlinx made R8
            // rewrite the stdlib too, after which release crashed at startup (IllegalAccessError: kotlin.collections.f).
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // The in-app language switcher (see AppLanguage) needs every language in the base APK: no per-language splits.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Android Lint is part of the quality gate: any warning fails the build.
    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Static analysis: `./gradlew detekt`. Configuration: config/detekt/detekt.yml (on top of the defaults).
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java")
}

// The app deliberately does not depend on AppCompat/Material/AndroidX: only framework widgets and the Kotlin stdlib —
// fewer classes to load on a cold start.
dependencies {
    testImplementation(libs.junit)
}
