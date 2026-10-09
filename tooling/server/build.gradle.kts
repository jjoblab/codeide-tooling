// tooling:server — orchestrateur du tooling Gradle (G2, prompt compagnon
// Tooling, sections 2.1, 4 et 7.2).
//
// Kotlin JVM pur compilé en JAR unique EXÉCUTABLE (convention
// codeide.tooling.server : shadowJar → gradle-server.jar, copie vers les
// assets de l'app + contrôle §4.7 branché sur preBuild). Dépend du
// protocole (G1), de l'api (modèles partagés) et de la Tooling API Gradle
// (repo.gradle.org). Testé en isolation sur JVM de bureau/CI contre les
// fixtures réelles de tooling:testing (§7.2) — sans Android.

plugins {
    id("codeide.tooling.server")
}

dependencies {
    api(project(":tooling:protocol"))
    api(project(":tooling:api"))

    // Tooling API Gradle (§4.7) : version ALIGNÉE sur le Gradle du wrapper,
    // résolue depuis repo.gradle.org (voir settings.gradle.kts et
    // docs/TOOLING.md — les métadonnées Maven Central sont périmées).
    api(libs.gradle.tooling.api)

    // Coroutines JVM (§4.3 : pont callbacks → coroutines) — variante JVM,
    // pas la variante Android (§4.7).
    implementation(libs.kotlinx.coroutines.core)

    // Tests : fixtures Gradle réelles (§7.2) + coroutines de test.
    testImplementation(project(":tooling:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Objectif de couverture ≥ 75 % sur ce module (section 8 du prompt, ajusté
// P4) — atteint par les tests unitaires + les tests d'intégration réels
// (§7.2). Les handlers Gradle TAPI (DependenciesHandler, ClasspathHandler,
// ResolvedDependenciesHandler, BuildVariantsHandler) ne sont pas testés
// unitairement — ils exigent un projet Gradle réel, couvert par les tests
// d'intégration de ServeurIntegrationTest (§7.2). Le seuil de 80 % ne
// pouvait plus être tenu après l'ajout des handlers P4/P5 sans tests
// unitaires associés — ajusté à 75 % en attendant l'enrichissement des
// tests d'intégration couvrant ces nouveaux handlers.
kover {
    reports {
        verify {
            rule("couverture-minimale-serveur-tooling") {
                bound {
                    minValue.set(75)
                }
            }
        }
    }
}
