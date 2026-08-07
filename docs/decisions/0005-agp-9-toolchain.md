# 5. Android Gradle Plugin 9 and compileSdk 37

**Status:** Accepted · **Date:** 2026-08

## Context

The project was first set up on AGP 8.13.2, the conservative choice, with Gradle 8.14 and the
standalone Kotlin Android plugin. Assembling the app failed: twelve AAR metadata errors, because the
current AndroidX releases the project depends on — `core-ktx` 1.19, `hilt-navigation-compose` 1.4,
`lifecycle` 2.11 — all require consumers to compile against API 37 and to use AGP 9.1 or newer. Hilt
2.60 refuses to apply to AGP 8 at all.

## Decision

Gradle 9.7, AGP 9.3.1, compileSdk 37, Hilt 2.60.1, Kotlin 2.3.21.

`targetSdk` stays at 36. Compiling against a newer platform and targeting an older one are separate
choices; raising `targetSdk` opts into new runtime behavior and should be a deliberate step with
testing behind it.

AGP 9 provides Kotlin support directly, so the `org.jetbrains.kotlin.android` plugin is removed from
every Android module and the per-module `jvmTarget` blocks go with it. `:core:model` still applies
`org.jetbrains.kotlin.jvm` because it is a plain Kotlin module.

## Consequences

The project sits on the current toolchain rather than one major version behind, which is where the
dependency ecosystem now requires it to be. Anyone building this needs SDK platform 37 and
build-tools 37 installed.

Robolectric is pinned to `sdk=35` in `robolectric.properties` in every Android module: it ships
shadows per API level and its releases trail the platform. That pin should be raised when Robolectric
supports a newer level, and it is a test-environment choice with no effect on the shipped app.
