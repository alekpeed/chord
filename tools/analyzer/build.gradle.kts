plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass.set("com.alekpeed.hearsay.tools.analyzer.MainKt")

    // A desktop has memory the tablet does not, which is the entire reason this exists: the
    // Maximum Quality profile on a long recording needs more heap than Android will ever grant.
    applicationDefaultJvmArgs = listOf("-Xmx12g", "-Dfile.encoding=UTF-8")
}

// The same analysis the app runs, unchanged. :core:audio is a plain Kotlin module with no Android
// dependency — a constraint kept from the start precisely so this could exist without a second
// implementation to drift out of step with the first.
dependencies {
    implementation(projects.core.audio)
    implementation(projects.core.model)

    testImplementation(libs.junit)
}
