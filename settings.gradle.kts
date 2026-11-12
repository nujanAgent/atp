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

rootProject.name = "atp"

// The wire module hosts and providers share (atp/wire), beside the AIDL and the fixtures.
include(":atp-wire")
project(":atp-wire").projectDir = file("atp/wire")

// The provider kit's engine (atp/provider): Binder, consent, grants and channels for any app that offers tools.
include(":atp-provider")
project(":atp-provider").projectDir = file("atp/provider")

// A provider in a few lines (samples/hello): the kit, one tool source and its XML.
include(":samples:hello")
