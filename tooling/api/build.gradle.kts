// tooling:api — modèles partagés du tooling Gradle (G2, prompt compagnon
// Tooling, section 2.1).
//
// Kotlin JVM pur, dépend de tooling:protocol uniquement (section 2.2) : les
// modèles du projet tels que les consommeront l'app Android (tooling:client
// via GradleToolingRepository, G3) et les tests — indépendants du format
// câble : le protocole transporte, l'api modélise. Les mappers protocol →
// api vivent ici aussi : une seule traduction, partagée.

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":tooling:protocol"))
}
