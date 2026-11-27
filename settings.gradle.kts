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

rootProject.name = "UniversalMedia"

include(":app")

// Core
include(":core:model")
include(":core:ui")
include(":core:database")
include(":core:datastore")

// Media engines (pure Kotlin where possible, for future iOS / desktop reuse)
include(":media:detection")

// Features
include(":feature:browser")
include(":feature:downloads")
include(":feature:player")
include(":feature:library")
include(":feature:connect")
include(":feature:settings")
