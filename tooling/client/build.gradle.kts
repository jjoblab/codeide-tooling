// tooling:client — client Android du tooling Gradle (G3, prompt compagnon
// Tooling, sections 5.1 à 5.3).
//
// Bibliothèque Android : hôte du socket (l'app EST le serveur — l'écoute
// s'ouvre AVANT le lancement du process JVM, aucun fichier de découverte,
// aucun polling), façade publique GradleToolingRepository (core:domain,
// implémentée ici), diffusion NON conflatante de la sortie des builds.
// Le lancement du process appartient à tooling:daemon (G4) — ce module ne
// fait que parler le protocole.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Ports du domaine (GradleToolingRepository) et traduction des
    // messages de l'orchestrateur vers ces modèles.
    api(project(":core:domain"))

    // Protocole (framing, messages) et modèles partagés du tooling.
    api(project(":tooling:protocol"))
    api(project(":tooling:api"))

    // Tests : session factice (§7.3 — SocketClient fake), temps virtuel.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

kover {
    reports {
        filters {
            excludes {
                // Colle LocalServerSocket/LocalSocket : exécution impossible
                // en JVM de test — Robolectric 4.17 n'a AUCUNE shadow de
                // ces classes (vérifié). La LOGIQUE (HandshakeApp,
                // GradleApiImpl) est couverte via les sessions factices du
                // §7.3 — la couture SessionTooling existe pour cela. Même
                // précédent que la colle Termux/JNI de core:terminal-runtime.
                classes(
                    "jo.codeide.tooling.client.GradleSocketServer*",
                    "jo.codeide.tooling.client.SessionSocketAndroid*",
                    "jo.codeide.tooling.client.ModuleToolingClient",
                    "jo.codeide.tooling.client.HiltWrapper_ModuleToolingClient",
                    "jo.codeide.tooling.client.GradleApiImpl_Factory",
                )
            }
        }
        verify {
            rule("couverture-minimale-client-tooling") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
