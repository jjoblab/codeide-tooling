# tooling:server

Orchestrateur du tooling Gradle (prompt compagnon Tooling, section 4) :
process JVM pur qui traduit le protocole de l'app en appels à la **Gradle
Tooling API**, et dont Gradle lance/garde vivant son PROPRE daemon séparé.
L'app Android est le serveur du socket, ce process est le client (§1).

Kotlin JVM pur, compilé en **JAR unique exécutable** (convention
`codeide.tooling.server` : `com.gradleup.shadow` → `gradle-server.jar`,
copie vers `app/src/main/assets/tooling/` + contrôle §4.7 branché sur
`preBuild` de l'app).

Statut : étape G2 (v0.27.0) livrée — voir le README du module, l'ADR
0040 et `docs/TOOLING.md`.
