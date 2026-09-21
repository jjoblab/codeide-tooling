// CodeIDE — paramètres du build multi-modules.
// Les 18 modules de la section 5.1 du prompt maître sont déclarés ici.

pluginManagement {
    repositories {
        google()
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

// Convention plugins de build-logic (build inclus).
includeBuild("build-logic")

rootProject.name = "CodeIDE"

include(":app")

// Socle transverse.
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:storage")
include(":core:logging")
include(":core:crash")
include(":core:ui")
include(":core:testing")

// Fonctionnalités.
include(":feature:onboarding")
include(":feature:home")
include(":feature:newproject")
include(":feature:settings")
include(":feature:diagnostics")
include(":feature:editor")
