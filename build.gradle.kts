// Top-level build file: plugins shared by all modules and the code-quality tooling.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

// Formatting: ktlint through Spotless. `./gradlew spotlessCheck` verifies, `./gradlew spotlessApply` fixes.
// The rules live in .editorconfig.
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
