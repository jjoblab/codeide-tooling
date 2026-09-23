# tooling:protocol

Protocole de communication entre l'app Android et le process JVM
orchestrateur du tooling Gradle (prompt compagnon Tooling, section 3) :
framing, catalogue de messages JSON, constantes partagées.

Kotlin JVM pur, sans dépendance Android — consommé par
`tooling:server` (JVM) et `tooling:client` (Android) à partir de G2/G3.

Statut : étape G1 (v0.26.0) livrée — voir le README du module, l'ADR
0039 et `docs/TOOLING.md`.
