# ADR-0001: Native Android with Kotlin and Jetpack Compose
Status: Accepted · 2026-10-10

Context: Android-only first release, heavy file/camera/PDF work, budget devices.
Decision: Native Kotlin, Jetpack Compose + Material 3, Hilt, Room, DataStore, WorkManager, CameraX. minSdk 29, target/compile 36.
Consequences: Direct access to PdfRenderer, PdfDocument, CameraX and ML Kit; no cross-platform layer. iOS later needs a new client.
