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
        maven("https://jitpack.io")
    }
}

rootProject.name = "Roam"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":data:source-api")
include(":data:source-drive")
include(":data:catalog")
include(":feature:player")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:downloader")
include(":feature:settings")
include(":update")
