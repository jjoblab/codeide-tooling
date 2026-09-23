# tooling:testing

Fixtures du tooling Gradle — prompt compagnon « Tooling Gradle
(client-serveur) », sections 2.1 et 7.2 (étape G1). Dépendance de test
uniquement, jamais en `implementation` (règle vérifiée par
`checkModuleDependencies`).

## Périmètre livré (étape G1, v0.26.0)

- **Quatre mini-projets Gradle réels** en ressources
  (`fixtures/gradle/`) :
  - `minimal-java` — build vert rapide, tâche `saluer` vérifiable,
    aucune dépendance externe (aucun réseau requis) ;
  - `erreur-compilation` — diagnostic de compilation attendu ;
  - `multi-module` — `:app` dépend de `:lib`, tâches qualifiées ;
  - `tache-longue` — tâche `endormir` pilotée par `-PdureeMs=...`
    (défaut borné 10 s, jamais infinie : un test en échec se termine
    seul), pour éprouver l'annulation.
- **`FixturesGradle`** : copie d'une fixture vers un répertoire
  temporaire frais — jamais construite en place (un build y écrirait
  des artéfacts et corromprait la ressource pour les tests suivants).

## Consommateurs (à venir)

`tooling:server` en `testImplementation` (tests d'intégration réels en
JVM pur, §7.2 — principal filet de sécurité de ce module).

## Dépendances

`:tooling:protocol` uniquement.
