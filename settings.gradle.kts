// codeide-tooling — paramètres du build.
// Dépôt extrait de jjoblab/CodeIDE le 2026-10-17 (git filter-repo,
// historique conservé — 315 commits). Il porte les modules JVM purs du
// domaine tooling ; tooling:client et tooling:daemon (Android, dépendants
// de :core:domain) RESTENT dans CodeIDE tant que les modules core:* ne
// sont pas publiés en binaire (docs CodeIDE, PLAN_SEPARATION_DEPOTS.md).
// Versionnement indépendant : tags v1.0.0+ propres à ce dépôt.

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
        // Tooling Gradle (G2) : org.gradle:gradle-tooling-api ne vit que sur
        // le dépôt officiel de Gradle — les métadonnées Maven Central de
        // cette coordonnée sont périmées (7.3-snapshot de 2021). Vérifié le
        // 2026-09-24 (docs/TOOLING.md du dépôt CodeIDE).
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
    }
}

// Convention plugins de build-logic (build inclus).
includeBuild("build-logic")

rootProject.name = "codeide-tooling"

include(":tooling:protocol")
include(":tooling:api")
include(":tooling:server")
include(":tooling:testing")
