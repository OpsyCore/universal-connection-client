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

rootProject.name = "universal-connection-client"

include(":app")
include(":core:platform")
include(":core:model")
include(":core:engine-api")
include(":core:singbox-config")
include(":core:app-logic")
include(":core:ios-infra")
include(":core:engine-singbox")
include(":core:vpn")
include(":core:config")
include(":core:smart")
