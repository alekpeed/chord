plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

// Deliberately free of Android and of any UI toolkit. The curriculum, the rule that decides whether
// a take counts, and the file it is written to are identical on a tablet and on a desktop; only the
// screen and the MIDI plumbing differ, and those live in the two apps.
dependencies {
    api(projects.core.model)

    testImplementation(libs.junit)
}
