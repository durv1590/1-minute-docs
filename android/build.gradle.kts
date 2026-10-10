plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

tasks.register("sharedTest") {
    description = "Runs the pure-Kotlin shared module tests."
    dependsOn(gradle.includedBuild("shared").task(":domain:test"))
    dependsOn(gradle.includedBuild("shared").task(":document:test"))
    dependsOn(gradle.includedBuild("shared").task(":imaging:test"))
}
