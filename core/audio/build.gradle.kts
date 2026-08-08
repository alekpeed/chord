plugins {
    alias(libs.plugins.kotlin.jvm)
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

// Deliberately a plain Kotlin module. Every analysis algorithm here operates on FloatArray and
// runs under an ordinary JVM test, so the recognizer can be proven against synthesized audio
// without a device in the loop.
dependencies {
    api(projects.core.model)

    testImplementation(libs.junit)
}
