# codeide-tooling

Tooling Gradle client-serveur **extrait de [CodeIDE](https://github.com/jjoblab/CodeIDE)**
le 2026-10-17 (`git filter-repo`, historique conservé — 315 commits).

## Modules

| Module | Artefact | Rôle |
| --- | --- | --- |
| `:tooling:protocol` | JAR | Protocole fil client ↔ serveur (framing « doré » v7, ADR 0039/0096 du dépôt CodeIDE) |
| `:tooling:api` | JAR | Modèles partagés de l'orchestrateur Tooling API |
| `:tooling:server` | `gradle-server.jar` (shadowJar) | Serveur exécutable unique, déployé dans les assets de CodeIDE (ADR 0040) |
| `:tooling:testing` | JAR | Fixtures et harnais d'intégration (projets Gradle réels embarqués) |

`tooling:client` et `tooling:daemon` (Android) **restent dans CodeIDE**
tant que les modules `core:*` dont ils dépendent (`core:domain`,
`core:testing`) ne sont pas publiés en binaire — leur historique est
conservé dans ce dépôt, leur retour se fera par restauration de chemins.

## Build

JDK 21 requis — dépôt **JVM pur**, aucun SDK Android nécessaire.

```bash
./gradlew spotlessCheck detekt checkModuleDependencies test :tooling:server:shadowJar
```

## Consommation

CodeIDE consommera les artefacts via **GitHub Packages** (publication à
venir), puis **JitPack** une fois le dépôt stabilisé — dans cet ordre.

## Versionnement

Indépendant de CodeIDE : tags `v1.0.0`+, compteur `version.properties`
propre (`VERSION_CODE = major*10000 + minor*100 + patch`).
