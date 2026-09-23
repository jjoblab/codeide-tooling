// tooling:protocol — protocole du tooling Gradle client-serveur (G1).
//
// Kotlin JVM pur, aucune dépendance interne au projet (prompt compagnon
// Tooling, section 2.2) : framing 4 octets + payload JSON, catalogue de
// messages kotlinx.serialization, constantes partagées par l'app Android
// et le process JVM. Testable et exécutable sans Android — les tests de
// sortie de phase (§3/§7.1) doivent être VERTS avant toute ligne de
// tooling:server / tooling:client.

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    // Sérialisation JSON des messages (§3.2) — la seule dépendance.
    api(libs.kotlinx.serialization.json)
}
