# 1. Native Android only

**Status:** Accepted · **Date:** 2026-08

## Context

The product is a tablet application read from a music stand while playing, with heavy on-device
audio work ahead of it. Cross-platform frameworks would let one codebase serve more platforms.

## Decision

Kotlin and Jetpack Compose, native Android only. No WebView for primary UI, no React, TypeScript,
Capacitor, Flutter or HTML application code.

## Consequences

The two things this app is actually made of — Media3 playback with a media session, and on-device
inference over audio buffers — are both places where a cross-platform layer costs latency and
control at exactly the point where they matter. Compose also gives the adaptive layout the tablet
target needs without a second UI implementation.

The cost is real: no iOS, no desktop, and no shared code if either is ever wanted. That trade is
accepted for the first product.
