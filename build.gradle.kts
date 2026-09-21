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

    // Règles de dépendance entre modules (tâche checkModuleDependencies).
    id("codeide.module-rules")
}
