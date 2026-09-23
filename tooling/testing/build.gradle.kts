// tooling:testing — fixtures du tooling Gradle (G1).
//
// Dépendance de test uniquement, jamais en implementation (prompt
// compagnon Tooling, section 2.2 — vérifié par checkModuleDependencies) :
// mini-projets Gradle réels consommés par les tests d'intégration de
// tooling:server (§7.2), fakes et fichiers de référence. Les fixtures
// vivent en ressources : le test copie le projet vers un répertoire
// temporaire avant de le construire — jamais construites en place.

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kover)
}

dependencies {
    // Les fakes et helpers exposent des types du protocole (§2.2).
    api(project(":tooling:protocol"))
}
