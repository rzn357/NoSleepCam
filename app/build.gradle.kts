plugins {
    alias(libs.plugins.android.application)
}

// -PstartupTrace=true вмикає мітки часу запуску й діагностику частоти кадрів у logcat
// (теги NSC-Startup, NSC-Fps, NSC-Req). У звичайній збірці виклики повністю вилучаються R8.
val startupTrace = providers.gradleProperty("startupTrace").map { it.toBoolean() }.orElse(false)

android {
    namespace = "com.example.nosleepcam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.nosleepcam"
        // Android 7.0: так само, як було в MAUI-версії. Honor 10 підтримується.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "STARTUP_TRACE", startupTrace.get().toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Оптимізація R8 лише коду додатка. Шаблонний packageScope = androidx/kotlin/kotlinx змушував R8
            // переписувати й stdlib, після чого release падав на старті (IllegalAccessError: kotlin.collections.f).
            optimization {
                enable = true
            }
            // Особистий додаток без власного ключа: підписуємо debug-ключем, щоб Release-збірку
            // (на ній і треба міряти швидкість запуску) можна було поставити на телефон.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Додаток навмисно не залежить від AppCompat/Material/AndroidX: лише фреймворкові віджети та Kotlin stdlib —
// менше класів для завантаження при холодному запуску.
dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
