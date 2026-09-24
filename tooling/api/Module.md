# tooling:api

Modèles partagés du tooling Gradle (prompt compagnon Tooling, section 2.1) :
les types du projet — lignes de sortie, états de build, instantanés de tas,
état de connexion, tâches, diagnostics, projets et modules — tels que les
consommeront l'app Android (`tooling:client` via `GradleToolingRepository`,
G3) et les tests, indépendamment du format câble.

Kotlin JVM pur, dépend de `tooling:protocol` uniquement (section 2.2) : le
protocole transporte (fichiers dorés de G1), l'api modélise — les mappers
protocol → api vivent ici, frontière unique entre les deux.

Statut : étape G2 (v0.27.0) livrée — voir le README du module, l'ADR
0040 et `docs/TOOLING.md`.
