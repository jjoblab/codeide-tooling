# tooling:server — orchestrateur du tooling Gradle

## Rôle

Le process JVM lancé par le daemon Android (G4) pour piloter les VRAIS
daemons Gradle. Architecture (§1 du prompt Tooling, validée par
l'expérience — à ne pas remettre en cause) :

- l'app Android est le SERVEUR du socket Unix, ce process est le CLIENT ;
- ce process n'est PAS le daemon Gradle : c'est un orchestrateur mince qui
  traduit le protocole en Tooling API, Gradle gère son propre daemon ;
- JSON `kotlinx.serialization` sur framing 4 octets (`tooling:protocol`) ;
- handshake obligatoire : version négociée + secret aléatoire (§4.4).

## Pièces (§4)

| Classe | Rôle |
|---|---|
| `ServerMain` | point d'entrée (`--socket`, `--secret`, `--log-level`, `--heap-intervalle-ms`) ; codes de sortie 0/2/3/4 |
| `ServerConfig` | analyse des arguments (secrets obligatoires, jamais loggés) |
| `SocketClient` | connexion UDS JDK 16+ (`runInterruptible` + délai) |
| `Handshake` | HelloRequest/HelloResponse, incompatibilité = erreur claire |
| `MessageDispatcher` | boucle de lecture + aiguillage borné (`limitedParallelism(6)`), jamais de traitement en ligne |
| `EventBusSocket` | file bornée 8192, `put()` bloquant (contre-pression, aucune perte), unique écrivain du socket |
| `GradleConnectorPool` | une `ProjectConnection` par projet (§4.2) |
| `BuildHandler` | `suspendCancellableCoroutine` sur `BuildLauncher.run(ResultHandler)`, annulation → `CancellationTokenSource` (§4.3) |
| `StreamingOutputStream` | sortie stdout/stderr Gradle → événements ligne à ligne (UTF-8 réassemblé, `\r` retiré) |
| `ProgressBridge` | évènements de tâches → `TaskStarted`/`TaskFinished` |
| `SyncHandler` | Resilient Sync (§5.3) : modèles résolus un par un, `PartialSyncResult` si échec partiel |
| `TasksHandler` / `DependenciesHandler` / `ModelHandler` | listes de tâches (arbre complet), dépendances inter-modules, résolution de modèle → `SyncResult` (ADR 0040) |
| `HeapMonitor` | tick périodique coroutine + instantané à la demande (§4.6) |
| `Journal` | sortie d'erreur = canal de journalisation du process séparé (tag `gradle-server`, §6) — exemption detekt documentée |

## Délais de garde (§7.5)

Build 30 min · sync 5 min · tâches 30 s · dépendances 30 s · modèle 5 min —
le dépassement devient `ErrorResponse(TIMEOUT)`, jamais un blocage muet.

## Tests (§7.2)

- **Unitaires** : `ServerConfigTest`, `StreamingOutputStreamTest`,
  `ProgressBridgeTest` (fakes des interfaces Tooling API) ;
- **Intégration RÉELS** (`ServeurIntegrationTest`) : l'orchestrateur complet
  tourne dans la JVM de test, connecté par un vrai socket Unix à une app
  factice, contre les 4 fixtures Gradle réelles de `tooling:testing` —
  handshake (secret, version, refus), builds minimal/erreur/multi-module,
  ANNULATION d'une tâche de 60 s (~2,6 s), tâches/sync/dépendances/modèle,
  ping/pong, tas, requête inconnue, arrêt propre (code 0). Le premier test
  qui touche une fixture amorce le daemon Gradle (~20 s), les suivants le
  réutilisent.

## Assemblage (§4.7)

`shadowJar` → `gradle-server.jar` (Main-Class `ServerMainKt`,
`mergeServiceFiles`), recopié vers `app/src/main/assets/tooling/` par
`copierJarVersAssets`, contrôlé par `controlerJarAssets` (présent + non
vide) branché sur `preBuild` de l'app : jamais d'APK sans orchestrateur.

## Dépendances

`tooling:protocol` + `tooling:api` (api) · `org.gradle:gradle-tooling-api`
9.7.1 alignée sur le wrapper (dépôt `repo.gradle.org`, voir
`docs/TOOLING.md`) · `kotlinx-coroutines-core` (JVM).
