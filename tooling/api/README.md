# tooling:api — modèles partagés du tooling Gradle

## Rôle

Deux niveaux de modèles cohabitent dans le tooling (prompt compagnon
Tooling) :

- **`tooling:protocol`** fige le format CÂBLE — les messages tels qu'ils
  voyagent sur le socket, gelés par les fichiers dorés de G1 ;
- **`tooling:api`** (ce module) porte les modèles du PROJET, libres
  d'évoluer avec l'app : c'est ce que `GradleToolingRepository`
  (core:domain, G3) expose aux features, et ce que les tests consomment.

Les mappers `protocol → api` (`Mappers.kt`) constituent la frontière unique
entre les deux : le client Android n'interprète jamais un message brut.

## Contenu

| Type | Rôle |
|---|---|
| `LigneSortieBuild` | une ligne de sortie de build (§5.3 `observeBuildOutput`) |
| `StatutBuild` / `EtatBuild` | cycle de vie d'un build vu du client |
| `InstantaneTas` | tas du process orchestrateur (§4.6) |
| `EtatConnexion` | liaison app ↔ orchestrateur (§5.3) |
| `InfoTache` | tâche du sélecteur « Exécuter » (§5.3) |
| `Diagnostic` | gravité + position fichier, pour `session.setDiagnostics` (§6) |
| `ModeleProjet` / `ModeleModule` | structure résolue depuis `IdeaProject` (réservé LSP) |

## Dépendances

`tooling:protocol` (api) — aucune autre, interne ou externe : le module
reste consommable par Android et par la JVM de test sans frais.

## Tests

`MappersApiTest` : fidélité de chaque traduction (champs, nullabilité,
listes).
