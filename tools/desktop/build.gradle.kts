import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Compose's own packaging rather than the `application` plugin's, which is not a preference.
//
// The application plugin flattens every dependency into a single lib directory, and Compose Desktop
// pulls two unrelated artifacts that are both named runtime-desktop-<version>.jar. One silently
// overwrote the other and the launcher died on NoClassDefFoundError for Composer — found by running
// the packaged build rather than by watching it compile. This packaging keeps them apart, and
// bundles a JRE, so the download runs on a machine with no Java installed at all.
compose.desktop {
    application {
        mainClass = "com.alekpeed.hearsay.tools.desktop.MainKt"

        // The reason to be on a desktop: the Maximum Quality profile's 8192-point transform over a
        // long recording needs more heap than Android will ever hand an app.
        jvmArgs += listOf("-Xmx8g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "hearsay"
            packageVersion = "1.0.0"
            description = "Turn a recording you own into an editable chord chart"
            vendor = "alekpeed"
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // The same analysis the tablet runs. :core:audio and :core:model are plain Kotlin with no
    // Android dependency, which is the constraint that lets this window exist without a second
    // implementation of the analysis behind it.
    implementation(projects.core.audio)
    implementation(projects.core.model)
    implementation(projects.tools.analyzer)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
