pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OneMinuteDocs"

// Pure-Kotlin modules (domain, document, imaging) live in their own build so they compile and test without the
// Android SDK; Gradle substitutes com.oneminutedocs.shared:<name> dependencies with these projects.
includeBuild("shared")

include(":app")
include(":core:designsystem")
include(":core:data")
include(":core:network")
include(":processing:pdf")
include(":processing:image")
include(":processing:ocr")
