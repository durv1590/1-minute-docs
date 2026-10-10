# ADR-0003: Pure-Kotlin shared build
Status: Accepted · 2026-10-10

Context: Business logic should be unit-testable on the JVM. Additionally, the initial development container's egress policy
blocked dl.google.com / maven.google.com, so the Android Gradle Plugin and AndroidX could not be resolved locally.
Decision: `android/shared` is a standalone Gradle build (Kotlin/JVM modules :domain, :document, :imaging), included into the
Android build with `includeBuild("shared")`. Android modules depend on `com.oneminutedocs.shared:<module>`.
Consequences: Shared logic compiles and is tested without the Android SDK. Android modules are compiled in CI (GitHub
Actions), which has the SDK and Google Maven.
