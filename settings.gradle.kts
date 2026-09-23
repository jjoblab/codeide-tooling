// CodeIDE — paramètres du build multi-modules.
// Modules de la section 5.1 du prompt maître (18 à la fin de la Phase 1) ;
// la Phase 2 (terminal intégré, prompt compagnon Terminal-1) ajoute
// core:bootstrap puis core:terminal-runtime et feature:terminal.

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
        // Bibliothèque d'édition code-editor (prompt compagnon, section 2.2) :
        // JitPack pour un build autonome sans jeton — GitHub Packages resterait
        // l'alternative documentée pour un usage local.
        maven { url = uri("https://jitpack.io") }
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

// Phase 2 — terminal intégré (prompt compagnon Terminal-1, section 2.1) :
// localisation des outils et environnement de sous-processus.
include(":core:bootstrap")
include(":core:terminal-runtime")

// Fonctionnalités.
include(":feature:onboarding")
include(":feature:home")
include(":feature:newproject")
include(":feature:settings")
include(":feature:diagnostics")
include(":feature:install")
include(":feature:editor")
// Terminal T5 : écran plein écran du terminal (prompt Terminal-1, section 5).
include(":feature:terminal")

// Outils (hors application — étape 9, ADR 0019 : harnais de vérification).
include(":tools:generateur")

// Phase 2 — tooling Gradle client-serveur (prompt compagnon Tooling,
// section 2.1) : G1 pose protocol et testing ; server/api/client/daemon
// arrivent aux étapes G2-G4.
include(":tooling:protocol")
include(":tooling:testing")
