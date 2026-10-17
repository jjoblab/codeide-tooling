// CodeIDE — build racine.
// Les plugins sont déclarés ici (apply false) pour un chargement de classeur
// unique sur tout le build ; les conventions de build-logic les appliquent
// ensuite module par module (voir ADR 0007 et docs/CONVENTIONS.md).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.kover) apply false
    // Tooling G2 : assemblage JAR unique de tooling:server (ADR 0040).
    alias(libs.plugins.shadow) apply false
    // Documentation API des modules purs explicitApi (audit étape 18) :
    // appliqué dans core:model et core:domain uniquement.
    alias(libs.plugins.dokka) apply false

    // Règles de dépendance entre modules (tâche checkModuleDependencies).
    id("codeide.module-rules")
}

// Publication (GitHub Packages d'abord, JitPack quand le dépôt est
// stable — docs CodeIDE PLAN_SEPARATION_DEPOTS.md §4) : les coordonnées
// suivent la convention JitPack multi-modules com.github.<user>.<repo>:<module>,
// pour que la consommation dev (composite) et binaire (Packages/JitPack)
// utilise exactement les MÊMES coordonnées — JitPack sert celles-ci sur
// un tag v*, sans reconfiguration des consommateurs.
val proprietesVersion = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

allprojects {
    group = "com.github.jjoblab.codeide-tooling"
    version = proprietesVersion.getProperty("VERSION_NAME")
}
